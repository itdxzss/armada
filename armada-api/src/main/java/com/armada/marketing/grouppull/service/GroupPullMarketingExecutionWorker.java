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
import com.armada.platform.protocol.model.result.GroupInviteResult;
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
 * <p>协议调用不持有数据库事务；每个状态写入都使用执行 ID、当前阶段和执行状态作为条件闸门。</p>
 */
@Component
public class GroupPullMarketingExecutionWorker {

    private static final Logger log = LoggerFactory.getLogger(GroupPullMarketingExecutionWorker.class);

    private static final Duration CREATE_GROUP_LEASE = Duration.ofSeconds(90);
    private static final Duration DEFAULT_STAGE_LEASE = Duration.ofSeconds(30);
    private static final Duration OFFLINE_RECHECK_DELAY = Duration.ofSeconds(15);
    private static final Duration SYSTEM_RECHECK_DELAY = Duration.ofSeconds(15);

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

    /** 尝试推进一条到期执行；未抢到租约时直接返回。 */
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
        if (!saveContact(
                builder.protocolRef(),
                marketer.getWsPhone(),
                prefix + "builder-to-marketer:" + marketer.getAccountId(),
                attempts)
                || !saveContact(
                marketer.protocolRef(),
                builder.getWsPhone(),
                prefix + "marketer-to-builder:" + marketer.getAccountId(),
                attempts)) {
            finalizer.skipBeforeGroup(execution.getId(), "建群账号与营销账号互加好友失败");
            return;
        }

        long now = System.currentTimeMillis();
        for (GroupPullMarketingExecutionMaterial material
                : mapper.selectExecutionMaterials(execution.getId())) {
            String operationId = prefix + "builder-to-material:" + material.getMaterialId();
            String failure = null;
            try {
                if (!saveContact(
                        builder.protocolRef(),
                        material.getMaterialPhone(),
                        operationId,
                        attempts)) {
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

    private boolean saveContact(
            ProtocolAccountRef account,
            String contact,
            String operationId,
            int attempts) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                contactPort.save(new ContactSaveCommand(account, contact, contact, operationId));
                return true;
            } catch (RuntimeException exception) {
                last = exception;
            }
        }
        log.warn(
                "拉群好友操作重试耗尽 operationId={} reason={}",
                operationId,
                last == null ? "unknown" : compactReason(last));
        return false;
    }

    private void createGroup(
            GroupPullMarketingExecution execution,
            GroupPullAccountRefRow builder) {
        GroupPullMarketingTask task = requireTask(execution.getTaskId());
        GroupPullAccountRefRow marketer = requireAccount(execution.getMarketingAccountId());
        String groupName = ensureGroupName(execution, task);
        String operationId = "group-pull:" + execution.getId() + ":create-group";
        RuntimeException last = null;
        for (int attempt = 0; attempt < GroupPullRetryPolicy.groupOperationAttempts(); attempt++) {
            try {
                GroupCreateResult result = groupCreatePort.create(new GroupCreateCommand(
                        builder.protocolRef(),
                        groupName,
                        List.of(marketer.getWsPhone()),
                        false,
                        operationId));
                saveCreatedGroup(execution, marketer, task, result);
                mapper.updateBlockReason(
                        execution.getTaskId(),
                        GroupPullBlockReason.NONE.code(),
                        System.currentTimeMillis());
                return;
            } catch (ProtocolException exception) {
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
        }
        return new ParticipantAttempt(Set.copyOf(successful), failureReason);
    }

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
            }
        }
        finalizer.fail(
                execution.getId(),
                "群发言权限设置失败：" + (last == null ? "未知错误" : compactReason(last)));
    }

    private void saveGroupInfo(
            GroupPullMarketingExecution execution,
            GroupPullAccountRefRow builder) {
        GroupPullAccountRefRow marketer = requireAccount(execution.getMarketingAccountId());
        List<GroupParticipantResult> members = null;
        String inviteUrl = null;
        List<String> nonFatalReasons = new ArrayList<>();
        try {
            members = memberListPort.list(new GroupMemberListQuery(
                    builder.protocolRef(),
                    execution.getGroupJid(),
                    "group-pull:" + execution.getId() + ":group-info:members"));
        } catch (RuntimeException exception) {
            nonFatalReasons.add("群人数获取失败：" + compactReason(exception));
        }
        try {
            GroupInviteResult invite = invitePort.getInvite(
                    builder.protocolRef(), execution.getGroupJid());
            inviteUrl = invite == null ? null : invite.inviteUrl();
        } catch (RuntimeException exception) {
            nonFatalReasons.add("群链接获取失败：" + compactReason(exception));
        }

        Integer memberCount = members == null ? null : members.size();
        String reason = joinReasons(nonFatalReasons);
        String finalInviteUrl = inviteUrl;
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
            }
        }
        mapper.updateBuilderExitStatus(
                execution.getId(), EXIT_FAILED, System.currentTimeMillis());
        finalizer.fail(
                execution.getId(),
                "建群账号退群失败：" + (last == null ? "未知错误" : compactReason(last)));
    }

    private void advance(
            GroupPullMarketingExecution execution,
            GroupPullExecutionStage nextStage,
            GroupPullExecutionStatus nextStatus) {
        long now = System.currentTimeMillis();
        advanceAt(execution, nextStage, nextStatus, now, now);
    }

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

    private record ParticipantAttempt(Set<String> successfulPhones, String failureReason) {
    }
}
