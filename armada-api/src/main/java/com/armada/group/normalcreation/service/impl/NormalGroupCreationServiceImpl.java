package com.armada.group.normalcreation.service.impl;

import com.armada.account.model.entity.AccountGroup;
import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.normalcreation.mapper.NormalGroupCreationMapper;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemInsert;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemIdentity;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberInsert;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberReplacement;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.SecondaryAdminInsert;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.TaskInsert;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.TaskExecutionScope;
import com.armada.group.normalcreation.model.dto.NormalGroupCreationCreateDTO;
import com.armada.group.normalcreation.model.dto.NormalGroupCreationSettingsDTO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationItemVO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationTaskDetailVO;
import com.armada.group.normalcreation.model.vo.NormalGroupCreationTaskVO;
import com.armada.group.normalcreation.service.NormalGroupCreationService;
import com.armada.group.normalcreation.support.NormalGroupCreationAdmissionGuard;
import com.armada.group.normalcreation.support.NormalGroupCreationSubject;
import com.armada.group.service.GroupFolderService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScopeAccess;
import com.armada.shared.security.DataScope;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 新建普群任务创建、冻结与查询实现。 */
@Service
public class NormalGroupCreationServiceImpl implements NormalGroupCreationService {

    private static final int MAX_GROUP_COUNT = 1_000;
    private static final int MAX_MEMBER_COUNT = 1_024;
    private static final int MAX_SECONDARY_ADMIN_COUNT = 1_024;
    private static final int MAX_SNAPSHOT_ROWS = 10_000;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;
    private static final int MEMBER_INSERT_BATCH_SIZE = 500;

    private final AccountGroupService accountGroupService;
    private final AccountProtocolLookupService accountLookupService;
    private final GroupFolderService groupFolderService;
    private final NormalGroupCreationMapper mapper;
    private final NormalGroupCreationCommandDispatcher commandDispatcher;
    private final NormalGroupCreationAdmissionGuard admissionGuard;
    private final TransactionTemplate transactionTemplate;
    private final SecureRandom random = new SecureRandom();

    public NormalGroupCreationServiceImpl(
            AccountGroupService accountGroupService,
            AccountProtocolLookupService accountLookupService,
            GroupFolderService groupFolderService,
            NormalGroupCreationMapper mapper,
            NormalGroupCreationCommandDispatcher commandDispatcher,
            NormalGroupCreationAdmissionGuard admissionGuard,
            PlatformTransactionManager transactionManager) {
        this.accountGroupService = accountGroupService;
        this.accountLookupService = accountLookupService;
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
        DataScope scope = DataScopeAccess.requireCurrent();
        long actorUserId = scope.ownerUserIdForCreate();
        if (userId != actorUserId) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "创建人与当前数据范围不一致");
        }
        String normalizedKey = requireIdempotencyKey(idempotencyKey);
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException(ErrorCode.TENANT_MISSING, "租户上下文缺失");
        }
        Long existingId = mapper.selectTaskIdByIdempotencyKey(
                tenantId, actorUserId, normalizedKey);
        if (existingId != null) {
            return mapper.selectTask(existingId, scope);
        }
        ValidatedRequest validated = validate(request, scope);
        admissionGuard.checkRate(tenantId, actorUserId);
        List<ProtocolAccountRef> creators =
                new ArrayList<>(strictOnlineGroupAccounts(validated.adminGroupId()));
        List<ProtocolAccountRef> members =
                new ArrayList<>(strictOnlineGroupAccounts(validated.memberGroupId()));
        List<ProtocolAccountRef> secondaryAdmins = validated.secondaryAdminGroupId() == null
                ? new ArrayList<>()
                : new ArrayList<>(strictOnlineGroupAccounts(validated.secondaryAdminGroupId()));
        creators.sort((left, right) -> left.armadaAccountId().compareTo(right.armadaAccountId()));
        Collections.shuffle(members, random);
        Collections.shuffle(secondaryAdmins, random);
        if (creators.size() < validated.groupCount()) {
            throw validation("管理员分组当前可执行在线账号不足，需要 " + validated.groupCount()
                    + " 个，实际 " + creators.size() + " 个");
        }
        if (!hasMemberCapacity(members, creators, validated.memberCount())) {
            throw validation("成员分组当前可执行在线账号不足，每群需要 "
                    + validated.memberCount() + " 个，实际 " + members.size() + " 个");
        }
        if (secondaryAdmins.size() < validated.secondaryAdminCount()) {
            throw validation("次管理员分组当前状态正常且在线的账号不足，每群需要 "
                    + validated.secondaryAdminCount() + " 个，实际 "
                    + secondaryAdmins.size() + " 个");
        }

        List<FrozenGroup> groups = new ArrayList<>(validated.groupCount());
        for (int index = 0; index < validated.groupCount(); index++) {
            ProtocolAccountRef creator = creators.get(index);
            List<ProtocolAccountRef> groupMembers = selectMembers(
                    members, creator.armadaAccountId(), validated.memberCount(), index);
            List<ProtocolAccountRef> groupSecondaryAdmins = selectSecondaryAdmins(
                    secondaryAdmins, creator, groupMembers,
                    validated.secondaryAdminCount(), index);
            groups.add(new FrozenGroup(
                    index + 1,
                    subject(validated.groupNameTemplate(), validated.startNo() + index,
                    validated.groupCount()),
                    creator,
                    groupMembers,
                    groupSecondaryAdmins));
        }

        admissionGuard.lockAndCheckCapacity(tenantId, validated.groupCount());

        long now = System.currentTimeMillis();
        TaskInsert task = new TaskInsert(
                actorUserId,
                normalizedKey,
                validated.adminGroupId(),
                validated.secondaryAdminGroupId(),
                validated.secondaryAdminCount(),
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
                actorUserId,
                now);
        try {
            if (mapper.insertTask(task) == 0) {
                return mapper.selectTask(
                        mapper.selectTaskIdByIdempotencyKeyForUpdate(
                                tenantId, actorUserId, normalizedKey),
                        scope);
            }
        } catch (DuplicateKeyException ex) {
            Long concurrentTaskId =
                    mapper.selectTaskIdByIdempotencyKeyForUpdate(
                            tenantId, actorUserId, normalizedKey);
            if (concurrentTaskId == null) {
                throw ex;
            }
            return mapper.selectTask(concurrentTaskId, scope);
        }
        Long taskId = mapper.selectTaskIdByIdempotencyKey(
                tenantId, actorUserId, normalizedKey);
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
        List<SecondaryAdminInsert> secondaryAdminRows = new ArrayList<>(
                validated.groupCount() * validated.secondaryAdminCount());
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
            for (int adminIndex = 0; adminIndex < group.secondaryAdmins().size(); adminIndex++) {
                ProtocolAccountRef secondaryAdmin = group.secondaryAdmins().get(adminIndex);
                ProtocolAccountRef anchor = group.members().get(adminIndex % group.members().size());
                secondaryAdminRows.add(new SecondaryAdminInsert(
                        taskId,
                        itemId,
                        adminIndex + 1,
                        secondaryAdmin.armadaAccountId(),
                        secondaryAdmin.protocolAccountId(),
                        secondaryAdmin.backend().name(),
                        secondaryAdmin.wsPhone(),
                        anchor.armadaAccountId(),
                        now));
            }
            dispatchItemIds.add(itemId);
        }
        for (int from = 0; from < memberRows.size(); from += MEMBER_INSERT_BATCH_SIZE) {
            int to = Math.min(from + MEMBER_INSERT_BATCH_SIZE, memberRows.size());
            mapper.insertMembers(memberRows.subList(from, to));
        }
        for (int from = 0; from < secondaryAdminRows.size(); from += MEMBER_INSERT_BATCH_SIZE) {
            int to = Math.min(from + MEMBER_INSERT_BATCH_SIZE, secondaryAdminRows.size());
            mapper.insertSecondaryAdmins(secondaryAdminRows.subList(from, to));
        }
        for (Long itemId : dispatchItemIds) {
            ItemWork item = mapper.selectItemWork(itemId);
            if (item == null) {
                throw unavailable();
            }
            commandDispatcher.enqueueContactPrepare(
                    item,
                    mapper.selectMemberWorks(item.id()),
                    mapper.selectSecondaryAdminWorks(item.id()));
        }
        mapper.refreshTaskSummary(taskId, System.currentTimeMillis());
        return mapper.selectTask(taskId, scope);
    }

    @Override
    public NormalGroupCreationTaskDetailVO detail(long taskId) {
        DataScope scope = DataScopeAccess.requireCurrent();
        NormalGroupCreationTaskVO task = mapper.selectTask(taskId, scope);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "新建普群任务不存在");
        }
        List<NormalGroupCreationItemVO> items = mapper.selectItems(taskId, scope);
        return new NormalGroupCreationTaskDetailVO(
                task, items, mapper.selectContactFailures(taskId, scope));
    }

    @Override
    public void retry(long taskId, long itemId, long userId) {
        DataScope scope = DataScopeAccess.requireCurrent();
        if (userId != scope.actorUserId()) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "操作人与当前数据范围不一致");
        }
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException(ErrorCode.TENANT_MISSING, "租户上下文缺失");
        }
        transactionTemplate.executeWithoutResult(status ->
                retryInTransaction(tenantId, taskId, itemId, scope));
    }

    private void retryInTransaction(
            Long tenantId,
            long taskId,
            long itemId,
            DataScope scope) {
        if (mapper.selectTask(taskId, scope) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "新建普群任务不存在");
        }
        TaskExecutionScope taskScope = mapper.selectTaskExecutionScope(tenantId, taskId);
        if (taskScope == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "新建普群任务不存在");
        }
        DataScopeAccess.requireAssignedOwner(taskScope.ownerUserId(), "新建普群任务");
        ItemWork item = mapper.selectItemWorkForUpdate(tenantId, itemId);
        validateRetryItem(item, taskId);
        long now = System.currentTimeMillis();
        if ("PREPARING_CONTACTS".equals(item.currentStep())) {
            List<MemberWork> currentMembers = mapper.selectMemberWorks(item.id());
            Map<Long, ProtocolAccountRef> replacements =
                    selectRetryMemberReplacements(item, currentMembers);
            List<MemberWork> dispatchMembers = applyRetryMemberReplacements(
                    item, currentMembers, replacements, now);
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

    private Map<Long, ProtocolAccountRef> selectRetryMemberReplacements(
            ItemWork item,
            List<MemberWork> members) {
        Long memberAccountGroupId = mapper.selectMemberAccountGroupId(item.taskId());
        if (memberAccountGroupId == null) {
            throw unavailable();
        }
        Set<Long> reservedAccountIds = new HashSet<>();
        reservedAccountIds.add(item.creatorAccountId());
        members.stream().map(MemberWork::memberAccountId).forEach(reservedAccountIds::add);
        List<ProtocolAccountRef> candidates =
                new ArrayList<>(strictOnlineGroupAccounts(memberAccountGroupId));
        Collections.shuffle(candidates, random);
        Map<Long, ProtocolAccountRef> candidatesByAccountId = new HashMap<>();
        for (ProtocolAccountRef candidate : candidates) {
            candidatesByAccountId.put(candidate.armadaAccountId(), candidate);
        }
        Map<Long, ProtocolAccountRef> replacements = new HashMap<>();
        for (MemberWork member : members) {
            if ("SUCCESS".equals(member.memberSavedCreatorStatus())) {
                continue;
            }
            ProtocolAccountRef current = candidatesByAccountId.get(member.memberAccountId());
            if (current != null) {
                if (!sameProtocolIdentity(member, current)) {
                    replacements.put(member.id(), current);
                }
                continue;
            }
            ProtocolAccountRef replacement = findReplacement(candidates, reservedAccountIds)
                    .orElseThrow(() -> validation(
                            "成员分组没有其他当前可执行在线账号，请将成员账号上线后重试"));
            replacements.put(member.id(), replacement);
            reservedAccountIds.add(replacement.armadaAccountId());
        }
        return Map.copyOf(replacements);
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
                .findFirst();
    }

    private List<ProtocolAccountRef> strictOnlineGroupAccounts(Long groupId) {
        try {
            return accountLookupService.findOnlineNormalStrictByGroupId(groupId);
        } catch (IllegalArgumentException ex) {
            throw validation("账号分组包含未明确配置 WEB/ANDROID 协议的在线账号");
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

    private ValidatedRequest validate(
            NormalGroupCreationCreateDTO request,
            DataScope scope) {
        if (request == null) {
            throw validation("请求不能为空");
        }
        if (request.adminAccountGroupId() == null
                || request.memberAccountGroupId() == null) {
            throw validation("管理员分组和成员分组不能为空");
        }
        AccountGroup adminGroup = accountGroupService.requireExisting(request.adminAccountGroupId());
        AccountGroup memberGroup = accountGroupService.requireExisting(request.memberAccountGroupId());
        List<Long> ownerUserIds = new ArrayList<>();
        ownerUserIds.add(adminGroup.getOwnerUserId());
        ownerUserIds.add(memberGroup.getOwnerUserId());
        Long secondaryAdminGroupId = request.secondaryAdminAccountGroupId();
        int secondaryAdminCount = 0;
        if (secondaryAdminGroupId != null) {
            ownerUserIds.add(accountGroupService.requireExisting(secondaryAdminGroupId).getOwnerUserId());
            secondaryAdminCount = positive(
                    request.secondaryAdminCount(), "每群次管理员数量", MAX_SECONDARY_ADMIN_COUNT);
        } else if (request.secondaryAdminCount() != null && request.secondaryAdminCount() != 0) {
            throw validation("未选择次管理员分组时，每群次管理员数量必须为 0");
        }
        if (request.folderId() != null) {
            ownerUserIds.add(
                    groupFolderService.requireExisting(request.folderId()).ownerUserId());
        }
        if (request.successMigrationGroupId() != null) {
            ownerUserIds.add(accountGroupService.requireExisting(
                    request.successMigrationGroupId()).getOwnerUserId());
        }
        if (request.failedMigrationGroupId() != null) {
            ownerUserIds.add(accountGroupService.requireExisting(
                    request.failedMigrationGroupId()).getOwnerUserId());
        }
        DataScopeAccess.requireSameOwner(ownerUserIds, "建群任务账号分组");
        DataScopeAccess.requireOwnedByActorForCreate(
                scope, ownerUserIds, "建群任务账号分组和群文件夹");
        int groupCount = positive(request.groupCount(), "建群数量", MAX_GROUP_COUNT);
        String source = textOrDefault(request.memberSource(), "CONTROLLED_GROUP")
                .toUpperCase(Locale.ROOT);
        if (!source.equals("CONTROLLED_GROUP") && !source.equals("EMPTY_GROUP")) {
            throw validation("成员来源仅支持 CONTROLLED_GROUP 或 EMPTY_GROUP");
        }
        int memberCount = source.equals("EMPTY_GROUP")
                ? 1 : positive(request.memberCount(), "每群成员数量", MAX_MEMBER_COUNT);
        long snapshotRows = (long) groupCount * (memberCount + secondaryAdminCount);
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
                request.adminAccountGroupId(), secondaryAdminGroupId,
                secondaryAdminCount, request.memberAccountGroupId(),
                memberCount, groupCount, nameTemplate, startNo, leavePolicy, speed,
                request.folderId(), request.successMigrationGroupId(),
                request.failedMigrationGroupId(), settings);
    }

    private List<ProtocolAccountRef> selectSecondaryAdmins(
            List<ProtocolAccountRef> candidates,
            ProtocolAccountRef creator,
            List<ProtocolAccountRef> members,
            int count,
            int offset) {
        Set<String> reservedPhones = new HashSet<>();
        reservedPhones.add(creator.wsPhone());
        members.stream().map(ProtocolAccountRef::wsPhone).forEach(reservedPhones::add);
        List<ProtocolAccountRef> result = new ArrayList<>(count);
        for (int step = 0; step < candidates.size() && result.size() < count; step++) {
            ProtocolAccountRef candidate = candidates.get((offset + step) % candidates.size());
            if (reservedPhones.add(candidate.wsPhone())) {
                result.add(candidate);
            }
        }
        if (result.size() < count) {
            throw validation("次管理员分组排除本群创群账号和目标成员后可用账号不足，每群需要 "
                    + count + " 个，实际可分配 " + result.size() + " 个");
        }
        return List.copyOf(result);
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

    private record FrozenGroup(
            int itemNo,
            String subject,
            ProtocolAccountRef creator,
            List<ProtocolAccountRef> members,
            List<ProtocolAccountRef> secondaryAdmins) {
    }

    private record ValidatedRequest(
            Long adminGroupId,
            Long secondaryAdminGroupId,
            int secondaryAdminCount,
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
