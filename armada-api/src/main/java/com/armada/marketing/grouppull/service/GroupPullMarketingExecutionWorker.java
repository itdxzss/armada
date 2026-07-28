package com.armada.marketing.grouppull.service;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.marketing.grouppull.mapper.GroupPullMarketingMapper;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecution;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingExecutionMaterial;
import com.armada.marketing.grouppull.model.entity.GroupPullMarketingTask;
import com.armada.marketing.grouppull.model.enums.GroupPullBlockReason;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStage;
import com.armada.marketing.grouppull.model.enums.GroupPullExecutionStatus;
import com.armada.marketing.grouppull.model.enums.GroupPullResourceStatus;
import com.armada.marketing.grouppull.model.enums.GroupPullSpeakPermission;
import com.armada.marketing.grouppull.model.vo.GroupPullAccountRefRow;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ContactSaveCommand;
import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.command.GroupMemberListQuery;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.result.GroupCreateParticipantResult;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.ContactPort;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.platform.protocol.port.GroupInvitePort;
import com.armada.platform.protocol.port.GroupLeavePort;
import com.armada.platform.protocol.port.GroupMemberListPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.platform.protocol.port.GroupSettingsPort;
import com.armada.platform.protocol.util.WhatsappJids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按短租约逐阶段推进一条拉群营销建群执行。
 *
 * <p>主流程依次完成好友准备、建群、添加营销号与料子、设置群权限、登记群信息、建群号退群和结果结算。
 * 每次调度只处理当前阶段，成功后通过条件更新推进到下一阶段，失败则由阶段自身决定终止、延迟或转人工处理。</p>
 *
 * <p>核心约束：</p>
 * <ul>
 *     <li>协议调用不持有数据库事务，避免远程请求拉长锁持有时间；</li>
 *     <li>每次状态写入都以执行 ID、读取时状态和当前阶段作为并发闸门；</li>
 *     <li>执行协议动作前重复检查任务资源锁，任务停止后不再产生新的外部副作用；</li>
 *     <li>建群使用稳定操作 ID；结果无法确认时转人工处理，禁止自动重建群；</li>
 *     <li>群人数和邀请链接属于非核心快照，读取失败不阻断已经创建的群继续结算。</li>
 * </ul>
 */
@Component
public class GroupPullMarketingExecutionWorker {

    private static final Logger log = LoggerFactory.getLogger(GroupPullMarketingExecutionWorker.class);

    /** 建群协议耗时通常高于其他阶段，因此使用独立的较长租约。 */
    private static final Duration CREATE_GROUP_LEASE = Duration.ofSeconds(90);
    private static final Duration DEFAULT_STAGE_LEASE = Duration.ofSeconds(30);
    private static final Duration OFFLINE_RECHECK_DELAY = Duration.ofSeconds(15);
    private static final Duration SYSTEM_RECHECK_DELAY = Duration.ofSeconds(15);

    /** 以下状态码只在执行明细内部使用，与协议层返回码无关。 */
    private static final int FRIEND_SUCCESS = 2;
    private static final int FRIEND_FAILED = 3;
    private static final int ADMIN_NOT_REQUIRED = 0;
    private static final int ADMIN_SET = 2;
    private static final int ADMIN_FAILED = 3;
    private static final int EXIT_NOT_REQUIRED = 0;
    private static final int EXIT_SUCCEEDED = 2;
    private static final int EXIT_FAILED = 3;

    private final GroupPullMarketingMapper mapper;
    private final GroupPullMarketingFinalizer finalizer;
    private final GroupLinkRegistryService groupRegistry;
    private final ContactPort contactPort;
    private final GroupCreatePort groupCreatePort;
    private final GroupParticipantPort participantPort;
    private final GroupSettingsPort settingsPort;
    private final GroupMemberListPort memberListPort;
    private final GroupInvitePort invitePort;
    private final GroupLeavePort leavePort;
    private final GroupPullMarketingMaterialEntryService materialEntryService;
    private final GroupPullMaterialEntryDelayPolicy materialEntryDelayPolicy;
    private final TransactionTemplate transactionTemplate;

    /** 注入阶段推进所需的持久化、协议端口和短事务组件。 */
    public GroupPullMarketingExecutionWorker(
            GroupPullMarketingMapper mapper,
            GroupPullMarketingFinalizer finalizer,
            GroupLinkRegistryService groupRegistry,
            ContactPort contactPort,
            GroupCreatePort groupCreatePort,
            GroupParticipantPort participantPort,
            GroupSettingsPort settingsPort,
            GroupMemberListPort memberListPort,
            GroupInvitePort invitePort,
            GroupLeavePort leavePort,
            GroupPullMarketingMaterialEntryService materialEntryService,
            GroupPullMaterialEntryDelayPolicy materialEntryDelayPolicy,
            PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.finalizer = finalizer;
        this.groupRegistry = groupRegistry;
        this.contactPort = contactPort;
        this.groupCreatePort = groupCreatePort;
        this.participantPort = participantPort;
        this.settingsPort = settingsPort;
        this.memberListPort = memberListPort;
        this.invitePort = invitePort;
        this.leavePort = leavePort;
        this.materialEntryService = materialEntryService;
        this.materialEntryDelayPolicy = materialEntryDelayPolicy;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 尝试推进一条到期执行；未抢到租约时直接返回。
     *
     * <p>入口统一完成活动状态校验、阶段租约竞争、任务锁检查和建群号可用性检查。未被阶段方法消费的
     * 运行时异常会被转换为系统阻塞原因，并把当前阶段短暂延后，等待下一轮调度恢复。</p>
     */
    public void process(Long executionId) {
        GroupPullMarketingExecution execution = mapper.selectExecutionById(executionId);
        if (!active(execution)) {
            return;
        }
        GroupPullExecutionStage stage = GroupPullExecutionStage.fromCode(execution.getCurrentStage());
        long now = System.currentTimeMillis();
        long leaseUntil = now + leaseDuration(stage).toMillis();
        if (mapper.tryLeaseExecution(
                executionId,
                execution.getExecutionStatus(),
                stage.code(),
                now,
                leaseUntil) != 1) {
            return;
        }
        if (cancelIfTaskNotLocked(execution)) {
            return;
        }

        GroupPullAccountRefRow builder = mapper.selectAccountRef(execution.getBuilderAccountId());
        if (!builderUsable(builder)) {
            finalizer.fail(executionId, "建群账号已封禁或不可用");
            return;
        }
        try {
            if (!online(builder)) {
                if (stage == GroupPullExecutionStage.ADD_MATERIALS) {
                    materialEntryService.processBuilderUnavailable(execution);
                } else {
                    mapper.delayExecution(
                            executionId,
                            execution.getExecutionStatus(),
                            stage.code(),
                            now + OFFLINE_RECHECK_DELAY.toMillis(),
                            null,
                            now);
                }
                return;
            }
            executeStage(execution, stage, builder);
        } catch (RuntimeException exception) {
            if (cancelIfTaskNotLocked(execution)) {
                return;
            }
            log.error(
                    "拉群营销执行阶段异常 executionId={} stage={}",
                    executionId,
                    stage,
                    exception);
            mapper.updateBlockReason(
                    execution.getTaskId(),
                    GroupPullBlockReason.SYSTEM_ERROR.code(),
                    System.currentTimeMillis());
            mapper.delayExecution(
                    executionId,
                    execution.getExecutionStatus(),
                    stage.code(),
                    System.currentTimeMillis() + SYSTEM_RECHECK_DELAY.toMillis(),
                    compactReason(exception),
                    System.currentTimeMillis());
        }
    }

    /** 把已取得租约的执行分发给唯一的当前阶段处理器。 */
    private void executeStage(
            GroupPullMarketingExecution execution,
            GroupPullExecutionStage stage,
            GroupPullAccountRefRow builder) {
        switch (stage) {
            case FRIEND_PREPARATION -> prepareFriends(execution, builder);
            case CREATE_GROUP -> createGroup(execution, builder);
            case ADD_MARKETER -> addMarketer(execution, builder);
            case ADD_MATERIALS -> materialEntryService.process(execution, builder.protocolRef());
            case SET_MARKETER_ADMIN -> setMarketerAdmin(execution, builder);
            case SET_SPEAK_PERMISSION -> setSpeakPermission(execution, builder);
            case SAVE_GROUP_INFO -> saveGroupInfo(execution, builder);
            case BUILDER_LEAVE -> leaveBuilder(execution, builder);
            case FINALIZE_RESULT -> finalizer.finalizeAfterStages(execution.getId());
            default -> log.debug(
                    "拉群营销执行阶段无需 worker 推进 executionId={} stage={}",
                    execution.getId(),
                    stage);
        }
    }

    /**
     * 建群前准备双向联系人关系，并逐条记录建群号添加料子联系人的结果。
     *
     * <p>营销号不可用或双方互加失败时尚未产生群资源，可以安全跳过本次执行；单个料子好友失败只记入
     * 料子明细，后续逐料阶段仍可按既定重试策略处理。</p>
     */
    private void prepareFriends(
            GroupPullMarketingExecution execution,
            GroupPullAccountRefRow builder) {
        GroupPullMarketingTask task = requireTask(execution.getTaskId());
        GroupPullAccountRefRow marketer = mapper.selectAccountRef(execution.getMarketingAccountId());
        if (!builderUsable(marketer) || !online(marketer)) {
            finalizer.skipBeforeGroup(execution.getId(), "营销账号离线或不可用");
            return;
        }
        int attempts = GroupPullRetryPolicy.friendAttempts(task.getFriendRetryLimit());
        String prefix = "group-pull:" + execution.getId() + ":friend:";
        ContactSaveResult builderToMarketer = saveContact(
                execution,
                builder.protocolRef(),
                marketer.getWsPhone(),
                prefix + "builder-to-marketer:" + marketer.getAccountId(),
                attempts);
        if (builderToMarketer == ContactSaveResult.TASK_STOPPED) {
            return;
        }
        ContactSaveResult marketerToBuilder = saveContact(
                execution,
                marketer.protocolRef(),
                builder.getWsPhone(),
                prefix + "marketer-to-builder:" + marketer.getAccountId(),
                attempts);
        if (marketerToBuilder == ContactSaveResult.TASK_STOPPED) {
            return;
        }
        if (builderToMarketer == ContactSaveResult.FAILED
                || marketerToBuilder == ContactSaveResult.FAILED) {
            finalizer.skipBeforeGroup(execution.getId(), "建群账号与营销账号互加好友失败");
            return;
        }

        long now = System.currentTimeMillis();
        for (GroupPullMarketingExecutionMaterial material
                : mapper.selectExecutionMaterials(execution.getId())) {
            String operationId = prefix + "builder-to-material:" + material.getMaterialId();
            String failure = null;
            try {
                ContactSaveResult result = saveContact(
                        execution,
                        builder.protocolRef(),
                        material.getMaterialPhone(),
                        operationId,
                        attempts);
                if (result == ContactSaveResult.TASK_STOPPED) {
                    return;
                }
                if (result == ContactSaveResult.FAILED) {
                    failure = "添加料子好友失败";
                }
            } catch (RuntimeException exception) {
                failure = compactReason(exception);
            }
            mapper.updateMaterialFriendResult(
                    material.getId(),
                    failure == null ? FRIEND_SUCCESS : FRIEND_FAILED,
                    failure,
                    now);
        }
        advance(execution, GroupPullExecutionStage.CREATE_GROUP, GroupPullExecutionStatus.PREPARING);
    }

    /**
     * 在任务仍持有资源锁的前提下，按配置次数重试联系人保存动作。
     *
     * @return 成功、重试耗尽或任务已停止
     */
    private ContactSaveResult saveContact(
            GroupPullMarketingExecution execution,
            ProtocolAccountRef account,
            String contact,
            String operationId,
            int attempts) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (cancelIfTaskNotLocked(execution)) {
                return ContactSaveResult.TASK_STOPPED;
            }
            try {
                contactPort.save(new ContactSaveCommand(account, contact, contact, operationId));
                return ContactSaveResult.SUCCEEDED;
            } catch (RuntimeException exception) {
                last = exception;
            }
        }
        log.warn(
                "拉群好友操作重试耗尽 operationId={} reason={}",
                operationId,
                last == null ? "unknown" : compactReason(last));
        if (cancelIfTaskNotLocked(execution)) {
            return ContactSaveResult.TASK_STOPPED;
        }
        return ContactSaveResult.FAILED;
    }

    /**
     * 使用稳定操作 ID 创建群，并在群 JID 落库后立即尽力捕获邀请链接。
     *
     * <p>幂等存储不可用时等待系统恢复；建群结果无法确认时转人工处理。只有协议明确返回普通失败时才
     * 允许按策略重试，避免对已经创建但响应丢失的群再次发起建群。</p>
     */
    private void createGroup(
            GroupPullMarketingExecution execution,
            GroupPullAccountRefRow builder) {
        GroupPullMarketingTask task = requireTask(execution.getTaskId());
        GroupPullAccountRefRow marketer = requireAccount(execution.getMarketingAccountId());
        String groupName = ensureGroupName(execution, task);
        String operationId = "group-pull:" + execution.getId() + ":create-group";
        RuntimeException last = null;
        for (int attempt = 0; attempt < GroupPullRetryPolicy.groupOperationAttempts(); attempt++) {
            if (cancelIfTaskNotLocked(execution)) {
                return;
            }
            try {
                GroupCreateResult result = groupCreatePort.create(new GroupCreateCommand(
                        builder.protocolRef(),
                        groupName,
                        List.of(marketer.getWsPhone()),
                        false,
                        operationId));
                saveCreatedGroup(execution, marketer, task, result);
                GroupPullMarketingInviteLinkSupport.captureAfterCreate(
                        mapper, invitePort, execution, builder, result.groupJid());
                mapper.updateBlockReason(
                        execution.getTaskId(),
                        GroupPullBlockReason.NONE.code(),
                        System.currentTimeMillis());
                return;
            } catch (ProtocolException exception) {
                if (cancelIfTaskNotLocked(execution)) {
                    return;
                }
                if (exception.errorCode() == ProtocolErrorCode.IDEMPOTENCY_STORE_UNAVAILABLE) {
                    delayForSystemDependency(execution);
                    return;
                }
                if (exception.errorCode() == ProtocolErrorCode.GROUP_CREATE_RESULT_UNCONFIRMED) {
                    long now = System.currentTimeMillis();
                    mapper.markExecutionManualReview(
                            execution.getId(),
                            GroupPullExecutionStage.CREATE_GROUP.code(),
                            compactReason(exception),
                            now);
                    mapper.updateBlockReason(
                            execution.getTaskId(),
                            GroupPullBlockReason.MANUAL_REVIEW.code(),
                            now);
                    return;
                }
                last = exception;
            }
        }
        finalizer.fail(
                execution.getId(),
                "创建群组失败：" + (last == null ? "未知错误" : compactReason(last)));
    }

    /**
     * 返回已经固化的群名；首次生成时锁定任务并按已命名执行数分配序号。
     *
     * <p>群名在正式建群前只写一次，使同一执行重试时始终使用相同名称。</p>
     */
    private String ensureGroupName(
            GroupPullMarketingExecution execution,
            GroupPullMarketingTask task) {
        if (StringUtils.hasText(execution.getGroupName())) {
            return execution.getGroupName();
        }
        String generated = transactionTemplate.execute(status -> {
            mapper.selectTaskForUpdate(execution.getTaskId());
            GroupPullMarketingExecution current = mapper.selectExecutionById(execution.getId());
            if (StringUtils.hasText(current.getGroupName())) {
                return current.getGroupName();
            }
            long sequence = mapper.countNamedExecutions(execution.getTaskId()) + 1;
            String name = GroupPullGroupNameGenerator.generate(task.getGroupNamePrefix(), sequence);
            if (mapper.saveGroupNameIfAbsent(execution.getId(), name, System.currentTimeMillis()) != 1) {
                throw new IllegalStateException("保存群名称时执行状态已变化");
            }
            return name;
        });
        if (!StringUtils.hasText(generated)) {
            throw new IllegalStateException("群名称生成失败");
        }
        return generated;
    }

    /**
     * 原子保存建群 JID、下一阶段和营销号额度确认结果。
     *
     * <p>若建群响应确认营销号已经入群，则跳过重复添加营销号并为首条料子计算等待时间；否则进入添加
     * 营销号阶段。该方法只负责核心建群结果，邀请链接在事务提交后单独读取。</p>
     */
    private void saveCreatedGroup(
            GroupPullMarketingExecution execution,
            GroupPullAccountRefRow marketer,
            GroupPullMarketingTask task,
            GroupCreateResult result) {
        if (result == null || !StringUtils.hasText(result.groupJid())) {
            throw new ProtocolException(
                    ProtocolErrorCode.GROUP_CREATE_RESULT_UNCONFIRMED,
                    "建群成功响应缺少群 JID");
        }
        boolean marketerJoined = createResultConfirmsParticipant(result, marketer.getWsPhone());
        int nextStage = marketerJoined
                ? GroupPullExecutionStage.ADD_MATERIALS.code()
                : GroupPullExecutionStage.ADD_MARKETER.code();
        transactionTemplate.executeWithoutResult(status -> {
            long now = System.currentTimeMillis();
            long nextExecuteAt = marketerJoined
                    ? materialEntryDelayPolicy.nextExecuteAt(
                            now, task.getMaterialEntryIntervalSeconds())
                    : now;
            if (mapper.markGroupCreated(new GroupPullMarketingMapper.GroupCreatedUpdate(
                    execution.getId(),
                    GroupPullExecutionStage.CREATE_GROUP.code(),
                    result.groupJid(),
                    nextStage,
                    nextExecuteAt,
                    now)) != 1) {
                throw new IllegalStateException("保存建群结果时执行状态已变化");
            }
            if (marketerJoined && mapper.confirmMarketingQuota(
                    execution.getTaskId(), marketer.getAccountId(), now) != 1) {
                throw new IllegalStateException("营销账号群额度确认失败");
            }
        });
    }

    /** 添加营销号入群，并在协议确认成功后核销预留群额度。 */
    private void addMarketer(
            GroupPullMarketingExecution execution,
            GroupPullAccountRefRow builder) {
        GroupPullMarketingTask task = requireTask(execution.getTaskId());
        GroupPullAccountRefRow marketer = requireAccount(execution.getMarketingAccountId());
        String marketerJid = WhatsappJids.userJid(marketer.getWsPhone());
        ParticipantAttempt result = updateParticipantsWithRetry(
                execution,
                builder.protocolRef(),
                List.of(marketerJid),
                GroupParticipantAction.ADD);
        if (result.taskStopped()) {
            return;
        }
        if (!result.successfulPhones().contains(phoneOf(marketerJid))) {
            finalizer.fail(execution.getId(), "营销账号添加失败：" + result.failureReason());
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            long now = System.currentTimeMillis();
            if (mapper.confirmMarketingQuota(
                    execution.getTaskId(), marketer.getAccountId(), now) != 1) {
                throw new IllegalStateException("营销账号群额度确认失败");
            }
            long nextExecuteAt = materialEntryDelayPolicy.nextExecuteAt(
                    now, task.getMaterialEntryIntervalSeconds());
            advanceAt(
                    execution,
                    GroupPullExecutionStage.ADD_MATERIALS,
                    GroupPullExecutionStatus.EXECUTING,
                    nextExecuteAt,
                    now);
        });
    }

    /**
     * 批量更新群成员并仅重试尚未成功的号码。
     *
     * <p>协议报告封群时同步记录群状态；任务停止时返回已完成的部分结果，由调用方直接结束当前轮处理。</p>
     */
    private ParticipantAttempt updateParticipantsWithRetry(
            GroupPullMarketingExecution execution,
            ProtocolAccountRef account,
            List<String> targets,
            GroupParticipantAction action) {
        Set<String> successful = new HashSet<>();
        String failureReason = null;
        for (int attempt = 0;
             attempt < GroupPullRetryPolicy.groupOperationAttempts()
                     && successful.size() < targets.size();
             attempt++) {
            if (cancelIfTaskNotLocked(execution)) {
                return new ParticipantAttempt(
                        Set.copyOf(successful), failureReason, true);
            }
            List<String> pending = targets.stream()
                    .filter(target -> !successful.contains(phoneOf(target)))
                    .toList();
            try {
                GroupParticipantBatchResult result = participantPort.updateParticipants(
                        account, execution.getGroupJid(), pending, action);
                if (result != null && result.results() != null) {
                    for (GroupParticipantBatchResult.Item item : result.results()) {
                        if (GroupPullRetryPolicy.isParticipantSuccess(item)) {
                            successful.add(phoneOf(item.jid()));
                        } else if (item != null) {
                            failureReason = firstText(item.rawStatus(), item.status());
                        }
                    }
                }
            } catch (ProtocolException exception) {
                if (GroupPullRetryPolicy.isGroupBanned(exception)) {
                    mapper.markGroupBanned(execution.getId(), System.currentTimeMillis());
                }
                failureReason = compactReason(exception);
            } catch (RuntimeException exception) {
                failureReason = compactReason(exception);
            }
            if (successful.size() < targets.size()
                    && cancelIfTaskNotLocked(execution)) {
                return new ParticipantAttempt(
                        Set.copyOf(successful), failureReason, true);
            }
        }
        return new ParticipantAttempt(Set.copyOf(successful), failureReason, false);
    }

    /**
     * 按发言权限和建群号退群配置决定是否需要把营销号提升为管理员。
     *
     * <p>不需要管理员时写入“不适用”状态，避免明细把跳过误判为未执行。</p>
     */
    private void setMarketerAdmin(
            GroupPullMarketingExecution execution,
            GroupPullAccountRefRow builder) {
        GroupPullMarketingTask task = requireTask(execution.getTaskId());
        GroupPullSpeakPermission permission =
                GroupPullSpeakPermission.fromCode(task.getSpeakPermission());
        boolean adminRequired = GroupPullRetryPolicy.adminRequired(
                permission, Boolean.TRUE.equals(task.getBuilderExitEnabled()));
        if (!adminRequired) {
            mapper.updateMarketerAdminStatus(
                    execution.getId(), ADMIN_NOT_REQUIRED, System.currentTimeMillis());
            advance(
                    execution,
                    GroupPullExecutionStage.SET_SPEAK_PERMISSION,
                    GroupPullExecutionStatus.EXECUTING);
            return;
        }
        GroupPullAccountRefRow marketer = requireAccount(execution.getMarketingAccountId());
        ParticipantAttempt result = updateParticipantsWithRetry(
                execution,
                builder.protocolRef(),
                List.of(WhatsappJids.userJid(marketer.getWsPhone())),
                GroupParticipantAction.PROMOTE);
        if (result.taskStopped()) {
            return;
        }
        if (result.successfulPhones().contains(phoneOf(marketer.getWsPhone()))) {
            mapper.updateMarketerAdminStatus(
                    execution.getId(), ADMIN_SET, System.currentTimeMillis());
            advance(
                    execution,
                    GroupPullExecutionStage.SET_SPEAK_PERMISSION,
                    GroupPullExecutionStatus.EXECUTING);
            return;
        }
        mapper.updateMarketerAdminStatus(
                execution.getId(), ADMIN_FAILED, System.currentTimeMillis());
        finalizer.fail(execution.getId(), "营销账号管理员设置失败：" + result.failureReason());
    }

    /** 设置群发言权限；配置为保持不变时不调用协议层。 */
    private void setSpeakPermission(
            GroupPullMarketingExecution execution,
            GroupPullAccountRefRow builder) {
        GroupPullSpeakPermission permission = GroupPullSpeakPermission.fromCode(
                requireTask(execution.getTaskId()).getSpeakPermission());
        if (permission == GroupPullSpeakPermission.UNCHANGED) {
            advance(
                    execution,
                    GroupPullExecutionStage.SAVE_GROUP_INFO,
                    GroupPullExecutionStatus.EXECUTING);
            return;
        }
        RuntimeException last = null;
        boolean enabled = permission == GroupPullSpeakPermission.UNMUTED;
        for (int attempt = 0; attempt < GroupPullRetryPolicy.groupOperationAttempts(); attempt++) {
            if (cancelIfTaskNotLocked(execution)) {
                return;
            }
            try {
                settingsPort.setSendMessagesAllowed(
                        builder.protocolRef(), execution.getGroupJid(), enabled);
                advance(
                        execution,
                        GroupPullExecutionStage.SAVE_GROUP_INFO,
                        GroupPullExecutionStatus.EXECUTING);
                return;
            } catch (ProtocolException exception) {
                if (GroupPullRetryPolicy.isGroupBanned(exception)) {
                    mapper.markGroupBanned(execution.getId(), System.currentTimeMillis());
                }
                last = exception;
                if (cancelIfTaskNotLocked(execution)) {
                    return;
                }
            }
        }
        finalizer.fail(
                execution.getId(),
                "群发言权限设置失败：" + (last == null ? "未知错误" : compactReason(last)));
    }

    /**
     * 汇总群人数和邀请链接，并在一个短事务中登记自建群、营销号成员关系和执行快照。
     *
     * <p>群人数读取失败会留下非致命原因；邀请链接优先复用建群后已保存的值，缺失时再通过协议补查。
     * 只有群资源登记或核心执行状态保存失败才终止执行。</p>
     */
    private void saveGroupInfo(
            GroupPullMarketingExecution execution,
            GroupPullAccountRefRow builder) {
        GroupPullAccountRefRow marketer = requireAccount(execution.getMarketingAccountId());
        List<GroupParticipantResult> members = null;
        List<String> nonFatalReasons = new ArrayList<>();
        if (cancelIfTaskNotLocked(execution)) {
            return;
        }
        try {
            members = memberListPort.list(new GroupMemberListQuery(
                    builder.protocolRef(),
                    execution.getGroupJid(),
                    "group-pull:" + execution.getId() + ":group-info:members"));
        } catch (RuntimeException exception) {
            nonFatalReasons.add("群人数获取失败：" + compactReason(exception));
        }
        if (cancelIfTaskNotLocked(execution)) {
            return;
        }
        GroupPullMarketingInviteLinkSupport.LookupResult invite =
                GroupPullMarketingInviteLinkSupport.resolveForSave(
                        invitePort, execution, builder);
        if (StringUtils.hasText(invite.failureReason())) {
            nonFatalReasons.add(invite.failureReason());
        }

        Integer memberCount = members == null ? null : members.size();
        String reason = joinReasons(nonFatalReasons);
        String finalInviteUrl = invite.inviteUrl();
        try {
            transactionTemplate.executeWithoutResult(status -> {
                long now = System.currentTimeMillis();
                Long groupLinkId = groupRegistry.registerSelfBuiltGroup(
                        execution.getGroupJid(),
                        execution.getGroupName(),
                        builder.getAccountId(),
                        builder.getWsPhone(),
                        memberCount,
                        now);
                groupRegistry.registerKnownMembership(
                        groupLinkId,
                        execution.getGroupJid(),
                        marketer.getAccountId(),
                        Integer.valueOf(ADMIN_SET).equals(execution.getMarketerAdminStatus()),
                        now);
                if (mapper.saveGroupInfo(
                        execution.getId(),
                        groupLinkId,
                        finalInviteUrl,
                        memberCount,
                        reason,
                        now) != 1) {
                    throw new IllegalStateException("保存群组核心信息时执行状态已变化");
                }
                advance(
                        execution,
                        GroupPullExecutionStage.BUILDER_LEAVE,
                        GroupPullExecutionStatus.EXECUTING);
            });
        } catch (RuntimeException exception) {
            finalizer.fail(execution.getId(), "群组关键信息保存失败：" + compactReason(exception));
        }
    }

    /**
     * 根据任务配置让建群号退出群，并在成功或无需退出时立即触发最终结算。
     *
     * <p>退群失败属于核心流程失败；协议报告封群时同时更新群状态，供明细和统计使用。</p>
     */
    private void leaveBuilder(
            GroupPullMarketingExecution execution,
            GroupPullAccountRefRow builder) {
        GroupPullMarketingTask task = requireTask(execution.getTaskId());
        if (!Boolean.TRUE.equals(task.getBuilderExitEnabled())) {
            mapper.updateBuilderExitStatus(
                    execution.getId(), EXIT_NOT_REQUIRED, System.currentTimeMillis());
            advance(
                    execution,
                    GroupPullExecutionStage.FINALIZE_RESULT,
                    GroupPullExecutionStatus.EXECUTING);
            finalizer.finalizeAfterStages(execution.getId());
            return;
        }
        RuntimeException last = null;
        for (int attempt = 0; attempt < GroupPullRetryPolicy.groupOperationAttempts(); attempt++) {
            if (cancelIfTaskNotLocked(execution)) {
                return;
            }
            try {
                leavePort.leave(builder.protocolRef(), execution.getGroupJid());
                mapper.updateBuilderExitStatus(
                        execution.getId(), EXIT_SUCCEEDED, System.currentTimeMillis());
                advance(
                        execution,
                        GroupPullExecutionStage.FINALIZE_RESULT,
                        GroupPullExecutionStatus.EXECUTING);
                finalizer.finalizeAfterStages(execution.getId());
                return;
            } catch (ProtocolException exception) {
                if (GroupPullRetryPolicy.isGroupBanned(exception)) {
                    mapper.markGroupBanned(execution.getId(), System.currentTimeMillis());
                }
                last = exception;
                if (cancelIfTaskNotLocked(execution)) {
                    return;
                }
            }
        }
        mapper.updateBuilderExitStatus(
                execution.getId(), EXIT_FAILED, System.currentTimeMillis());
        finalizer.fail(
                execution.getId(),
                "建群账号退群失败：" + (last == null ? "未知错误" : compactReason(last)));
    }

    /** 立即推进到下一阶段。 */
    private void advance(
            GroupPullMarketingExecution execution,
            GroupPullExecutionStage nextStage,
            GroupPullExecutionStatus nextStatus) {
        long now = System.currentTimeMillis();
        advanceAt(execution, nextStage, nextStatus, now, now);
    }

    /**
     * 以读取时状态和阶段为条件推进执行，并同步当前内存对象供同一调用栈继续使用。
     *
     * <p>更新行数不是 1 表示租约期间状态已变化，调用方必须停止继续产生副作用。</p>
     */
    private void advanceAt(
            GroupPullMarketingExecution execution,
            GroupPullExecutionStage nextStage,
            GroupPullExecutionStatus nextStatus,
            long nextExecuteAt,
            long now) {
        if (mapper.advanceExecutionStage(
                execution.getId(),
                execution.getExecutionStatus(),
                execution.getCurrentStage(),
                nextStage.code(),
                nextStatus.code(),
                nextExecuteAt,
                now) != 1) {
            throw new IllegalStateException("推进拉群执行阶段失败");
        }
        execution.setCurrentStage(nextStage.code());
        execution.setExecutionStatus(nextStatus.code());
        execution.setStageRetryCount(0);
        execution.setNextExecuteAt(nextExecuteAt);
    }

    /** 标记系统依赖阻塞，并保留当前阶段等待下一轮调度重试。 */
    private void delayForSystemDependency(GroupPullMarketingExecution execution) {
        long now = System.currentTimeMillis();
        mapper.updateBlockReason(
                execution.getTaskId(), GroupPullBlockReason.SYSTEM_ERROR.code(), now);
        mapper.delayExecution(
                execution.getId(),
                execution.getExecutionStatus(),
                execution.getCurrentStage(),
                now + SYSTEM_RECHECK_DELAY.toMillis(),
                null,
                now);
    }

    private GroupPullMarketingTask requireTask(Long taskId) {
        GroupPullMarketingTask task = mapper.selectTaskById(taskId);
        if (task == null) {
            throw new IllegalStateException("拉群营销任务不存在 taskId=" + taskId);
        }
        return task;
    }

    private GroupPullAccountRefRow requireAccount(Long accountId) {
        GroupPullAccountRefRow account = mapper.selectAccountRef(accountId);
        if (account == null) {
            throw new IllegalStateException("拉群营销账号不存在 accountId=" + accountId);
        }
        return account;
    }

    /**
     * 仅允许资源仍被当前任务锁定时启动下一次协议动作。
     *
     * @return 任务已不再运行并已触发执行取消时返回 {@code true}
     */
    private boolean cancelIfTaskNotLocked(GroupPullMarketingExecution execution) {
        GroupPullMarketingTask task = mapper.selectTaskById(execution.getTaskId());
        if (task != null
                && Integer.valueOf(GroupPullResourceStatus.LOCKED.code())
                        .equals(task.getResourceStatus())) {
            return false;
        }
        finalizer.cancelForTaskRelease(execution.getId());
        return true;
    }

    private static boolean active(GroupPullMarketingExecution execution) {
        return execution != null
                && (Integer.valueOf(GroupPullExecutionStatus.PREPARING.code())
                        .equals(execution.getExecutionStatus())
                || Integer.valueOf(GroupPullExecutionStatus.EXECUTING.code())
                        .equals(execution.getExecutionStatus()));
    }

    private static boolean builderUsable(GroupPullAccountRefRow account) {
        return account != null
                && Integer.valueOf(AccountStateCode.NORMAL).equals(account.getAccountState())
                && StringUtils.hasText(account.getProtocolAccountId())
                && StringUtils.hasText(account.getWsPhone());
    }

    private static boolean online(GroupPullAccountRefRow account) {
        return account != null
                && Integer.valueOf(AccountLoginStateCode.ONLINE).equals(account.getLoginState());
    }

    private static Duration leaseDuration(GroupPullExecutionStage stage) {
        return stage == GroupPullExecutionStage.CREATE_GROUP
                ? CREATE_GROUP_LEASE
                : DEFAULT_STAGE_LEASE;
    }

    private static boolean createResultConfirmsParticipant(
            GroupCreateResult result,
            String participantPhone) {
        if (result.results() == null) {
            return false;
        }
        String expected = phoneOf(participantPhone);
        for (GroupCreateParticipantResult item : result.results()) {
            if (item == null || !expected.equals(phoneOf(item.jid()))) {
                continue;
            }
            if ("OK".equalsIgnoreCase(item.status())
                    || "ALREADY_IN".equalsIgnoreCase(item.status())
                    || "200".equals(item.rawStatus())) {
                return true;
            }
        }
        return false;
    }

    private static String phoneOf(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        int separator = normalized.indexOf('@');
        return separator < 0 ? normalized : normalized.substring(0, separator);
    }

    private static String firstText(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first;
        }
        return StringUtils.hasText(second) ? second : "协议未确认目标状态";
    }

    private static String compactReason(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (!StringUtils.hasText(message)) {
            message = throwable == null ? "未知错误" : throwable.getClass().getSimpleName();
        }
        return message.length() <= 180 ? message : message.substring(0, 180);
    }

    private static String joinReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return null;
        }
        String joined = String.join(";", reasons.stream().distinct().toList());
        return joined.length() <= 255 ? joined : joined.substring(0, 255);
    }

    private enum ContactSaveResult {
        SUCCEEDED,
        FAILED,
        TASK_STOPPED
    }

    private record ParticipantAttempt(
            Set<String> successfulPhones,
            String failureReason,
            boolean taskStopped) {
    }
}
