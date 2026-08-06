package com.armada.group.normalcreation.service.impl;

import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.normalcreation.mapper.NormalGroupCreationMapper;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemInsert;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemIdentity;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberInsert;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.TaskInsert;
import com.armada.group.normalcreation.model.dto.NormalGroupCreationCreateDTO;
import com.armada.group.normalcreation.model.dto.NormalGroupCreationSettingsDTO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationItemVO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationTaskDetailVO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationTaskVO;
import com.armada.group.normalcreation.service.NormalGroupCreationEventPublisher;
import com.armada.group.normalcreation.service.NormalGroupCreationService;
import com.armada.group.normalcreation.support.NormalGroupCreationAdmissionGuard;
import com.armada.group.service.GroupFolderService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 新建普群任务创建、冻结与查询实现。 */
@Service
public class NormalGroupCreationServiceImpl implements NormalGroupCreationService {

    private static final Logger log =
            LoggerFactory.getLogger(NormalGroupCreationServiceImpl.class);

    private static final int MAX_GROUP_COUNT = 1_000;
    private static final int MAX_MEMBER_COUNT = 1_024;
    private static final int MAX_SNAPSHOT_ROWS = 10_000;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;
    private static final int MEMBER_INSERT_BATCH_SIZE = 500;

    private final AccountGroupService accountGroupService;
    private final AccountProtocolLookupService accountLookupService;
    private final GroupFolderService groupFolderService;
    private final NormalGroupCreationMapper mapper;
    private final NormalGroupCreationEventPublisher eventPublisher;
    private final NormalGroupCreationAdmissionGuard admissionGuard;
    private final SecureRandom random = new SecureRandom();

    public NormalGroupCreationServiceImpl(
            AccountGroupService accountGroupService,
            AccountProtocolLookupService accountLookupService,
            GroupFolderService groupFolderService,
            NormalGroupCreationMapper mapper,
            NormalGroupCreationEventPublisher eventPublisher,
            NormalGroupCreationAdmissionGuard admissionGuard) {
        this.accountGroupService = accountGroupService;
        this.accountLookupService = accountLookupService;
        this.groupFolderService = groupFolderService;
        this.mapper = mapper;
        this.eventPublisher = eventPublisher;
        this.admissionGuard = admissionGuard;
    }

    @Override
    @Transactional
    public NormalGroupCreationTaskVO create(
            String idempotencyKey, NormalGroupCreationCreateDTO request, long userId) {
        String normalizedKey = requireIdempotencyKey(idempotencyKey);
        Long existingId = mapper.selectTaskIdByIdempotencyKey(normalizedKey);
        if (existingId != null) {
            return mapper.selectTask(existingId);
        }
        ValidatedRequest validated = validate(request);
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException(ErrorCode.TENANT_MISSING, "租户上下文缺失");
        }
        admissionGuard.checkRate(tenantId, userId);
        List<ProtocolAccountRef> creators = new ArrayList<>(
                accountLookupService.findOnlineNormalByGroupId(validated.adminGroupId()));
        List<ProtocolAccountRef> members = new ArrayList<>(
                accountLookupService.findOnlineNormalByGroupId(validated.memberGroupId()));
        if (creators.size() < validated.groupCount()) {
            throw validation("管理员分组可用在线账号不足，需要 " + validated.groupCount()
                    + " 个，实际 " + creators.size() + " 个");
        }
        if (members.size() < validated.memberCount()) {
            throw validation("成员分组可用在线账号不足，每群需要 " + validated.memberCount()
                    + " 个，实际 " + members.size() + " 个");
        }
        creators.sort((left, right) -> left.armadaAccountId().compareTo(right.armadaAccountId()));
        Collections.shuffle(members, random);

        List<FrozenGroup> groups = new ArrayList<>(validated.groupCount());
        for (int index = 0; index < validated.groupCount(); index++) {
            ProtocolAccountRef creator = creators.get(index);
            groups.add(new FrozenGroup(
                    index + 1,
                    subject(validated.groupNameTemplate(), validated.startNo() + index,
                            validated.groupCount()),
                    creator,
                    selectMembers(members, creator.armadaAccountId(),
                            validated.memberCount(), index)));
        }

        admissionGuard.lockAndCheckCapacity(tenantId, validated.groupCount());

        long now = System.currentTimeMillis();
        TaskInsert task = new TaskInsert(
                normalizedKey,
                validated.adminGroupId(),
                validated.memberGroupId(),
                validated.memberCount(),
                validated.groupCount(),
                validated.groupNameTemplate(),
                validated.startNo(),
                validated.leavePolicy(),
                validated.speed(),
                validated.folderId(),
                validated.successMigrationGroupId(),
                validated.failedMigrationGroupId(),
                validated.settings().sendMessagesAllowed(),
                validated.settings().editGroupSettingsAllowed(),
                validated.settings().addMembersAllowed(),
                validated.settings().joinApprovalEnabled(),
                validated.settings().ephemeralDurationSeconds(),
                userId,
                now);
        try {
            if (mapper.insertTask(task) == 0) {
                return mapper.selectTask(
                        mapper.selectTaskIdByIdempotencyKeyForUpdate(normalizedKey));
            }
        } catch (DuplicateKeyException ex) {
            Long concurrentTaskId =
                    mapper.selectTaskIdByIdempotencyKeyForUpdate(normalizedKey);
            if (concurrentTaskId == null) {
                throw ex;
            }
            return mapper.selectTask(concurrentTaskId);
        }
        Long taskId = mapper.selectTaskIdByIdempotencyKey(normalizedKey);
        if (taskId == null) {
            throw unavailable();
        }
        List<ItemInsert> itemRows = groups.stream()
                .map(group -> new ItemInsert(
                        taskId,
                        group.itemNo(),
                        group.subject(),
                        group.creator().armadaAccountId(),
                        group.creator().protocolAccountId(),
                        group.creator().backend().name(),
                        group.creator().wsPhone(),
                        now))
                .toList();
        mapper.insertItems(itemRows);
        Map<Integer, Long> itemIds = new HashMap<>(validated.groupCount());
        for (ItemIdentity identity : mapper.selectItemIdentities(taskId)) {
            itemIds.put(identity.itemNo(), identity.id());
        }
        if (itemIds.size() != validated.groupCount()) {
            throw unavailable();
        }

        List<MemberInsert> memberRows = new ArrayList<>(
                validated.groupCount() * validated.memberCount());
        List<InitialDispatch> dispatches = new ArrayList<>(validated.groupCount());
        for (FrozenGroup group : groups) {
            Long itemId = itemIds.get(group.itemNo());
            if (itemId == null) {
                throw unavailable();
            }
            for (int memberIndex = 0; memberIndex < group.members().size(); memberIndex++) {
                ProtocolAccountRef member = group.members().get(memberIndex);
                memberRows.add(new MemberInsert(
                        taskId,
                        itemId,
                        memberIndex + 1,
                        member.armadaAccountId(),
                        member.protocolAccountId(),
                        member.backend().name(),
                        member.wsPhone(),
                        now));
            }
            dispatches.add(new InitialDispatch(
                    taskId, itemId, group.creator().armadaAccountId()));
        }
        for (int from = 0; from < memberRows.size(); from += MEMBER_INSERT_BATCH_SIZE) {
            int to = Math.min(from + MEMBER_INSERT_BATCH_SIZE, memberRows.size());
            mapper.insertMembers(memberRows.subList(from, to));
        }
        afterCommit(dispatches);
        return mapper.selectTask(taskId);
    }

    @Override
    public NormalGroupCreationTaskDetailVO detail(long taskId) {
        NormalGroupCreationTaskVO task = mapper.selectTask(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "新建普群任务不存在");
        }
        List<NormalGroupCreationItemVO> items = mapper.selectItems(taskId);
        return new NormalGroupCreationTaskDetailVO(task, items);
    }

    @Override
    public void retry(long taskId, long itemId, long userId) {
        ItemWork item = mapper.selectItemWork(itemId);
        if (item == null || !item.taskId().equals(taskId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "新建普群明细不存在");
        }
        if ("RESULT_UNKNOWN".equals(item.status())
                || "CREATED_PARTIAL".equals(item.status())) {
            throw validation("群已创建或结果不确定，必须先完成对账，不能直接重试协议操作");
        }
        long now = System.currentTimeMillis();
        if (mapper.resetItemForRetry(taskId, itemId, now) == 0) {
            throw validation("当前明细状态不允许重试");
        }
        log.info("新建普群失败项人工重试 tenantId={} taskId={} itemId={} operatorUserId={}",
                TenantContext.get(), taskId, itemId, userId);
        publishSafely(stage(item.currentStep()), TenantContext.get(), taskId,
                itemId, item.creatorAccountId());
    }

    private ValidatedRequest validate(NormalGroupCreationCreateDTO request) {
        if (request == null) {
            throw validation("请求不能为空");
        }
        if (request.adminAccountGroupId() == null || request.memberAccountGroupId() == null) {
            throw validation("管理员分组和成员分组不能为空");
        }
        accountGroupService.requireExisting(request.adminAccountGroupId());
        accountGroupService.requireExisting(request.memberAccountGroupId());
        if (request.folderId() != null) {
            groupFolderService.requireExisting(request.folderId());
        }
        if (request.successMigrationGroupId() != null) {
            accountGroupService.requireExisting(request.successMigrationGroupId());
        }
        if (request.failedMigrationGroupId() != null) {
            accountGroupService.requireExisting(request.failedMigrationGroupId());
        }
        int groupCount = positive(request.groupCount(), "建群数量", MAX_GROUP_COUNT);
        String source = textOrDefault(request.memberSource(), "CONTROLLED_GROUP")
                .toUpperCase(Locale.ROOT);
        if (!source.equals("CONTROLLED_GROUP") && !source.equals("EMPTY_GROUP")) {
            throw validation("成员来源仅支持 CONTROLLED_GROUP 或 EMPTY_GROUP");
        }
        int memberCount = source.equals("EMPTY_GROUP")
                ? 1 : positive(request.memberCount(), "每群成员数量", MAX_MEMBER_COUNT);
        long snapshotRows = (long) groupCount * memberCount;
        if (snapshotRows > MAX_SNAPSHOT_ROWS) {
            throw validation("本次计划群成员快照共 " + snapshotRows
                    + " 条，超过单任务上限 " + MAX_SNAPSHOT_ROWS + " 条，请拆分任务");
        }
        String nameTemplate = requiredText(request.groupNameTemplate(), "群名模板");
        if (nameTemplate.length() > 128) {
            throw validation("群名模板不能超过 128 个字符");
        }
        int startNo = request.startNo() == null ? 1 : request.startNo();
        if (startNo < 0) {
            throw validation("起始序号不能小于 0");
        }
        String leavePolicy = textOrDefault(request.creatorLeavePolicy(), "KEEP")
                .toUpperCase(Locale.ROOT);
        if (!leavePolicy.equals("KEEP") && !leavePolicy.equals("LEAVE")) {
            throw validation("建群人策略仅支持 KEEP 或 LEAVE");
        }
        String speed = textOrDefault(request.speed(), "NORMAL").toUpperCase(Locale.ROOT);
        if (!"NORMAL".equals(speed)) {
            throw validation("本期执行速度仅支持 NORMAL");
        }
        NormalGroupCreationSettingsDTO settings = request.settings() == null
                ? NormalGroupCreationSettingsDTO.defaults() : request.settings().normalized();
        if (settings.ephemeralDurationSeconds() < 0) {
            throw validation("限时消息秒数不能小于 0");
        }
        return new ValidatedRequest(
                request.adminAccountGroupId(), request.memberAccountGroupId(),
                memberCount, groupCount, nameTemplate, startNo, leavePolicy, speed,
                request.folderId(), request.successMigrationGroupId(),
                request.failedMigrationGroupId(), settings);
    }

    private List<ProtocolAccountRef> selectMembers(
            List<ProtocolAccountRef> candidates, Long creatorId, int count, int offset) {
        List<ProtocolAccountRef> result = new ArrayList<>(count);
        for (int step = 0; step < candidates.size() && result.size() < count; step++) {
            ProtocolAccountRef candidate = candidates.get((offset + step) % candidates.size());
            if (!candidate.armadaAccountId().equals(creatorId)) {
                result.add(candidate);
            }
        }
        if (result.size() < count) {
            throw validation("成员分组在排除当前建群账号后，可用账号不足 " + count + " 个");
        }
        return List.copyOf(result);
    }

    private void afterCommit(List<InitialDispatch> dispatches) {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException(ErrorCode.TENANT_MISSING, "租户上下文缺失");
        }
        Runnable publish = () -> dispatches.forEach(row -> publishSafely(
                "PREPARE", tenantId, row.taskId(), row.itemId(), row.creatorAccountId()));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publish.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publish.run();
            }
        });
    }

    private void publishSafely(
            String action, Long tenantId, Long taskId, Long itemId, Long creatorAccountId) {
        try {
            eventPublisher.publish(action, tenantId, taskId, itemId, creatorAccountId);
        } catch (RuntimeException ex) {
            log.warn("新建普群消息首发失败，将由低频补偿恢复 tenantId={} taskId={} itemId={} action={}",
                    tenantId, taskId, itemId, action);
        }
    }

    private static String stage(String currentStep) {
        return switch (currentStep) {
            case "PREPARING_CONTACTS" -> "PREPARE";
            case "CREATING_GROUP" -> "CREATE";
            case "POST_PROCESSING" -> "POST_PROCESS";
            default -> throw validation("当前明细没有可重试阶段");
        };
    }

    private static String subject(String template, int no, int groupCount) {
        String value = template.contains("{no}")
                ? template.replace("{no}", String.valueOf(no))
                : groupCount == 1 ? template : template + "-" + no;
        if (value.length() > 128) {
            throw validation("生成后的群名不能超过 128 个字符: " + value);
        }
        return value;
    }

    private static int positive(Integer value, String field, int max) {
        if (value == null || value <= 0 || value > max) {
            throw validation(field + "必须在 1 到 " + max + " 之间");
        }
        return value;
    }

    private static String requireIdempotencyKey(String value) {
        String result = requiredText(value, "Idempotency-Key");
        if (result.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw validation("Idempotency-Key 不能超过 64 个字符");
        }
        return result;
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw validation(field + "不能为空");
        }
        return value.trim();
    }

    private static String textOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private static BusinessException unavailable() {
        return new BusinessException(
                ErrorCode.AUTH_SERVICE_UNAVAILABLE, "新建普群任务初始化失败，请稍后重试");
    }

    private record InitialDispatch(Long taskId, Long itemId, Long creatorAccountId) {
    }

    private record FrozenGroup(
            int itemNo,
            String subject,
            ProtocolAccountRef creator,
            List<ProtocolAccountRef> members) {
    }

    private record ValidatedRequest(
            Long adminGroupId,
            Long memberGroupId,
            int memberCount,
            int groupCount,
            String groupNameTemplate,
            int startNo,
            String leavePolicy,
            String speed,
            Long folderId,
            Long successMigrationGroupId,
            Long failedMigrationGroupId,
            NormalGroupCreationSettingsDTO settings) {
    }
}
