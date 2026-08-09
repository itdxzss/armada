package com.armada.group.normalcreation.service.impl;

import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.normalcreation.mapper.NormalGroupCreationMapper;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemInsert;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemIdentity;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberInsert;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberReplacement;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.TaskInsert;
import com.armada.group.normalcreation.model.dto.NormalGroupCreationCreateDTO;
import com.armada.group.normalcreation.model.dto.NormalGroupCreationSettingsDTO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationItemVO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationTaskDetailVO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationTaskVO;
import com.armada.group.normalcreation.service.NormalGroupCreationService;
import com.armada.group.normalcreation.support.NormalGroupCreationAdmissionGuard;
import com.armada.group.normalcreation.support.NormalGroupCreationSubject;
import com.armada.group.service.GroupFolderService;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.ProtocolAccountRuntimeStatus;
import com.armada.platform.protocol.port.AccountRuntimeStatusPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 新建普群任务创建、冻结与查询实现。 */
@Service
public class NormalGroupCreationServiceImpl implements NormalGroupCreationService {

    private static final Logger log = LoggerFactory.getLogger(NormalGroupCreationServiceImpl.class);

    private static final int MAX_GROUP_COUNT = 1_000;
    private static final int MAX_MEMBER_COUNT = 1_024;
    private static final int MAX_SNAPSHOT_ROWS = 10_000;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;
    private static final int MEMBER_INSERT_BATCH_SIZE = 500;

    private final AccountGroupService accountGroupService;
    private final AccountProtocolLookupService accountLookupService;
    private final AccountRuntimeStatusPort runtimeStatusPort;
    private final GroupFolderService groupFolderService;
    private final NormalGroupCreationMapper mapper;
    private final NormalGroupCreationCommandDispatcher commandDispatcher;
    private final NormalGroupCreationAdmissionGuard admissionGuard;
    private final TransactionTemplate transactionTemplate;
    private final SecureRandom random = new SecureRandom();

    public NormalGroupCreationServiceImpl(
            AccountGroupService accountGroupService,
            AccountProtocolLookupService accountLookupService,
            AccountRuntimeStatusPort runtimeStatusPort,
            GroupFolderService groupFolderService,
            NormalGroupCreationMapper mapper,
            NormalGroupCreationCommandDispatcher commandDispatcher,
            NormalGroupCreationAdmissionGuard admissionGuard,
            PlatformTransactionManager transactionManager) {
        this.accountGroupService = accountGroupService;
        this.accountLookupService = accountLookupService;
        this.runtimeStatusPort = runtimeStatusPort;
        this.groupFolderService = groupFolderService;
        this.mapper = mapper;
        this.commandDispatcher = commandDispatcher;
        this.admissionGuard = admissionGuard;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    @Transactional
    public NormalGroupCreationTaskVO create(
            String idempotencyKey, NormalGroupCreationCreateDTO request, long userId) {
        String normalizedKey = requireIdempotencyKey(idempotencyKey);
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException(ErrorCode.TENANT_MISSING, "租户上下文缺失");
        }
        Long existingId = mapper.selectTaskIdByIdempotencyKey(tenantId, normalizedKey);
        if (existingId != null) {
            return mapper.selectTask(existingId);
        }
        ValidatedRequest validated = validate(request);
        admissionGuard.checkRate(tenantId, userId);
        List<ProtocolAccountRef> creators =
                new ArrayList<>(strictOnlineGroupAccounts(validated.adminGroupId()));
        List<ProtocolAccountRef> members =
                new ArrayList<>(strictOnlineGroupAccounts(validated.memberGroupId()));
        creators.sort((left, right) -> left.armadaAccountId().compareTo(right.armadaAccountId()));
        Collections.shuffle(members, random);
        creators = selectRuntimeOnlineCreators(creators, validated.groupCount());
        if (creators.size() < validated.groupCount()) {
            throw validation("管理员分组当前可执行在线账号不足，需要 " + validated.groupCount()
                    + " 个，实际 " + creators.size() + " 个");
        }
        members = selectRuntimeOnlineMembers(
                members, creators, validated.memberCount(), validated.groupCount());
        if (!hasMemberCapacity(members, creators, validated.memberCount())) {
            throw validation("成员分组当前可执行在线账号不足，每群需要 "
                    + validated.memberCount() + " 个，实际 " + members.size() + " 个");
        }

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
                        mapper.selectTaskIdByIdempotencyKeyForUpdate(tenantId, normalizedKey));
            }
        } catch (DuplicateKeyException ex) {
            Long concurrentTaskId =
                    mapper.selectTaskIdByIdempotencyKeyForUpdate(tenantId, normalizedKey);
            if (concurrentTaskId == null) {
                throw ex;
            }
            return mapper.selectTask(concurrentTaskId);
        }
        Long taskId = mapper.selectTaskIdByIdempotencyKey(tenantId, normalizedKey);
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
        List<Long> dispatchItemIds = new ArrayList<>(validated.groupCount());
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
            dispatchItemIds.add(itemId);
        }
        for (int from = 0; from < memberRows.size(); from += MEMBER_INSERT_BATCH_SIZE) {
            int to = Math.min(from + MEMBER_INSERT_BATCH_SIZE, memberRows.size());
            mapper.insertMembers(memberRows.subList(from, to));
        }
        for (Long itemId : dispatchItemIds) {
            ItemWork item = mapper.selectItemWork(itemId);
            if (item == null) {
                throw unavailable();
            }
            commandDispatcher.enqueueContactPrepare(item, mapper.selectMemberWorks(item.id()));
        }
        mapper.refreshTaskSummary(taskId, System.currentTimeMillis());
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
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException(ErrorCode.TENANT_MISSING, "租户上下文缺失");
        }
        ItemWork observedItem = mapper.selectItemWork(itemId);
        validateRetryItem(observedItem, taskId);
        RetryContactProbe contactProbe = "PREPARING_CONTACTS".equals(observedItem.currentStep())
                ? probeRetryContactMembers(observedItem)
                : null;
        transactionTemplate.executeWithoutResult(status ->
                retryInTransaction(tenantId, taskId, itemId, contactProbe));
    }

    private void retryInTransaction(
            Long tenantId,
            long taskId,
            long itemId,
            RetryContactProbe contactProbe) {
        ItemWork item = mapper.selectItemWorkForUpdate(tenantId, itemId);
        validateRetryItem(item, taskId);
        long now = System.currentTimeMillis();
        if ("PREPARING_CONTACTS".equals(item.currentStep())) {
            if (contactProbe == null) {
                throw unavailable();
            }
            List<MemberWork> currentMembers = mapper.selectMemberWorks(item.id());
            if (!currentMembers.equals(contactProbe.observedMembers())) {
                throw validation("新建普群明细已变更，请刷新后重试");
            }
            List<MemberWork> dispatchMembers = applyRetryMemberReplacements(
                    item, currentMembers, contactProbe.replacements(), now);
            commandDispatcher.enqueueFailedContactPrepare(item, dispatchMembers);
        } else if (List.of("CREATING_GROUP", "APPLYING_SETTINGS", "LEAVING_GROUP")
                .contains(item.currentStep())) {
            String action = switch (item.currentStep()) {
                case "CREATING_GROUP" -> "GROUP_CREATE";
                case "APPLYING_SETTINGS" -> "GROUP_SETTINGS_APPLY";
                case "LEAVING_GROUP" -> "GROUP_LEAVE";
                default -> throw validation("当前阶段不支持重试");
            };
            String commandId = commandDispatcher.enqueueCreatorAction(item, action);
            if (mapper.retryProtocolAction(
                    item.id(), item.currentStep(), commandId, now) != 1) {
                throw unavailable();
            }
        } else {
            throw validation("当前阶段不支持重试");
        }
        mapper.refreshTaskSummary(taskId, now);
    }

    private static void validateRetryItem(ItemWork item, long taskId) {
        if (item == null || !item.taskId().equals(taskId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "新建普群明细不存在");
        }
        if (!"FAILED".equals(item.status())) {
            throw validation("仅允许重试结果明确失败的新建普群明细");
        }
        if (!List.of("PREPARING_CONTACTS", "CREATING_GROUP", "APPLYING_SETTINGS", "LEAVING_GROUP")
                .contains(item.currentStep())) {
            throw validation("当前阶段不支持重试");
        }
    }

    private RetryContactProbe probeRetryContactMembers(ItemWork item) {
        Long memberAccountGroupId = mapper.selectMemberAccountGroupId(item.taskId());
        if (memberAccountGroupId == null) {
            throw unavailable();
        }
        List<MemberWork> members = mapper.selectMemberWorks(item.id());
        Set<Long> reservedAccountIds = new HashSet<>();
        reservedAccountIds.add(item.creatorAccountId());
        members.stream().map(MemberWork::memberAccountId).forEach(reservedAccountIds::add);
        List<ProtocolAccountRef> candidates = null;
        Map<Long, ProtocolAccountRef> replacements = new HashMap<>();
        for (MemberWork member : members) {
            if ("SUCCESS".equals(member.memberSavedCreatorStatus())) {
                continue;
            }
            Optional<ProtocolAccountRef> current =
                    accountLookupService.findActiveProtocolRef(member.memberAccountId());
            if (current.isPresent() && runtimeOnline(current.get())) {
                if (!sameProtocolIdentity(member, current.get())) {
                    replacements.put(member.id(), current.get());
                }
                continue;
            }
            if (candidates == null) {
                candidates = new ArrayList<>(strictOnlineGroupAccounts(memberAccountGroupId));
                Collections.shuffle(candidates, random);
            }
            ProtocolAccountRef replacement = findReplacement(candidates, reservedAccountIds)
                    .orElseThrow(() -> validation(
                            "成员分组没有其他当前可执行在线账号，请将成员账号上线后重试"));
            replacements.put(member.id(), replacement);
            reservedAccountIds.add(replacement.armadaAccountId());
        }
        return new RetryContactProbe(List.copyOf(members), Map.copyOf(replacements));
    }

    private List<MemberWork> applyRetryMemberReplacements(
            ItemWork item,
            List<MemberWork> members,
            Map<Long, ProtocolAccountRef> replacements,
            long now) {
        for (MemberWork member : members) {
            ProtocolAccountRef replacement = replacements.get(member.id());
            if (replacement != null) {
                replaceMember(item, member, replacement, now);
            }
        }
        return replacements.isEmpty() ? members : mapper.selectMemberWorks(item.id());
    }

    private void replaceMember(
            ItemWork item,
            MemberWork member,
            ProtocolAccountRef replacement,
            long now) {
        MemberReplacement row = new MemberReplacement(
                member.id(), item.id(), member.memberAccountId(), replacement.armadaAccountId(),
                replacement.protocolAccountId(), replacement.backend().name(),
                replacement.wsPhone(), now);
        if (mapper.replaceMember(row) != 1) {
            throw unavailable();
        }
    }

    private Optional<ProtocolAccountRef> findReplacement(
            List<ProtocolAccountRef> candidates,
            Set<Long> reservedAccountIds) {
        return candidates.stream()
                .filter(candidate -> !reservedAccountIds.contains(candidate.armadaAccountId()))
                .filter(this::runtimeOnline)
                .findFirst();
    }

    private List<ProtocolAccountRef> strictOnlineGroupAccounts(Long groupId) {
        try {
            return accountLookupService.findOnlineNormalStrictByGroupId(groupId);
        } catch (IllegalArgumentException ex) {
            throw validation("账号分组包含未明确配置 WEB/ANDROID 协议的在线账号");
        }
    }

    private List<ProtocolAccountRef> selectRuntimeOnlineCreators(
            List<ProtocolAccountRef> candidates,
            int requiredCount) {
        List<ProtocolAccountRef> result = new ArrayList<>(requiredCount);
        for (ProtocolAccountRef candidate : candidates) {
            if (runtimeOnline(candidate)) {
                result.add(candidate);
                if (result.size() >= requiredCount) {
                    break;
                }
            }
        }
        return List.copyOf(result);
    }

    private List<ProtocolAccountRef> selectRuntimeOnlineMembers(
            List<ProtocolAccountRef> candidates,
            List<ProtocolAccountRef> creators,
            int memberCount,
            int groupCount) {
        int targetPoolSize = Math.min(
                candidates.size(), memberCount + groupCount - 1);
        List<ProtocolAccountRef> result = new ArrayList<>(targetPoolSize + 1);
        for (ProtocolAccountRef candidate : candidates) {
            if (runtimeOnline(candidate)) {
                result.add(candidate);
                if (result.size() >= targetPoolSize
                        && hasMemberCapacity(result, creators, memberCount)) {
                    break;
                }
            }
        }
        return List.copyOf(result);
    }

    private boolean runtimeOnline(ProtocolAccountRef account) {
        try {
            ProtocolAccountRuntimeStatus status = runtimeStatusPort.status(account);
            return status != null && status.online();
        } catch (ProtocolException ex) {
            log.warn("普群账号实时状态查询失败 accountId={} backend={} errorCode={}",
                    account.armadaAccountId(), account.backend(), ex.errorCode());
            return false;
        }
    }

    private static boolean hasMemberCapacity(
            List<ProtocolAccountRef> members,
            List<ProtocolAccountRef> creators,
            int memberCount) {
        if (members.size() < memberCount) {
            return false;
        }
        if (members.size() > memberCount) {
            return true;
        }
        Set<Long> memberIds = new HashSet<>();
        members.stream().map(ProtocolAccountRef::armadaAccountId).forEach(memberIds::add);
        return creators.stream().noneMatch(
                creator -> memberIds.contains(creator.armadaAccountId()));
    }

    private static boolean sameProtocolIdentity(
            MemberWork member,
            ProtocolAccountRef account) {
        return member.memberAccountId().equals(account.armadaAccountId())
                && member.memberProtocolAccountId().equals(account.protocolAccountId())
                && member.memberProtocolBackend().equals(account.backend().name())
                && member.memberWsPhone().equals(account.wsPhone());
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
        String nameTemplate = NormalGroupCreationSubject.normalizeTemplate(
                request.groupNameTemplate());
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

    private String subject(String template, int no, int groupCount) {
        if (NormalGroupCreationSubject.isAutomatic(template)) {
            return NormalGroupCreationSubject.randomPrefix(random);
        }
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

    private record RetryContactProbe(
            List<MemberWork> observedMembers,
            Map<Long, ProtocolAccountRef> replacements) {
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
