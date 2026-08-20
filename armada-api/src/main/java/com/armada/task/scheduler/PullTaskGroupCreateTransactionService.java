package com.armada.task.scheduler;

import com.armada.group.service.GroupLinkUrls;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.GroupCreateParticipantResult;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskGroupCreateTransition;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskStandardGroupSetting;
import com.armada.task.model.entity.PullTaskStandardSetting;
import com.armada.task.model.enums.PullTaskAccountEntryMode;
import com.armada.task.model.enums.PullTaskExecutionReasonCode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupCreateStep;
import com.armada.task.model.enums.PullTaskGroupSettingTiming;
import com.armada.task.model.enums.PullTaskSelectionMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 新群模式建群阶段的短事务与持久化检查点。 */
@Service
public class PullTaskGroupCreateTransactionService {

    private static final int INITIAL_SOURCE = 1;
    private static final int GROUP_SUBJECT_MAX_LENGTH = 100;
    private static final Set<String> PARTICIPANT_SUCCESS =
            Set.of("OK", "SUCCESS", "ALREADY_IN", "200");
    private static final Set<ProtocolErrorCode> DEFINITELY_NOT_CREATED = EnumSet.of(
            ProtocolErrorCode.BAD_REQUEST,
            ProtocolErrorCode.ACCOUNT_NOT_ONLINE,
            ProtocolErrorCode.UNSUPPORTED_BACKEND,
            ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED);

    private final PullTaskGroupCreatePersistence persistence;
    private final PullTaskGroupCreateResources resources;

    public PullTaskGroupCreateTransactionService(
            PullTaskGroupCreatePersistence persistence,
            PullTaskGroupCreateResources resources) {
        this.persistence = persistence;
        this.resources = resources;
    }

    /** 步骤 1：冻结建群人、初始次管理员、初始站台、群名和幂等键。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult prepareRoles(
            PullTaskGroupExecution candidate,
            long now,
            long retryDelayMs) {
        return withTenant(candidate.getTenantId(), () -> {
            PullTaskStandardSetting setting =
                    persistence.settingMapper().selectByTaskId(candidate.getTaskId());
            PullTaskStandardGroupSetting groupSetting =
                    persistence.groupSettingMapper().selectByTaskId(candidate.getTaskId());
            if (setting == null || setting.getCreatorGroupId() == null
                    || setting.getManagerGroupId() == null) {
                return pauseInvalid(candidate, now);
            }

            Set<Long> selectedIds = new LinkedHashSet<>();
            ProtocolAccountRef creator = selectStable(
                    online(setting.getCreatorGroupId()), candidate.getSeq(), selectedIds);
            if (creator == null) {
                return defer(candidate, PullTaskExecutionReasonCode.GROUP_CREATOR_UNAVAILABLE,
                        now + retryDelayMs, now);
            }
            selectedIds.add(creator.armadaAccountId());

            ProtocolAccountRef manager = selectStable(
                    online(setting.getManagerGroupId()), candidate.getSeq(), selectedIds);
            if (manager == null) {
                return defer(candidate, PullTaskExecutionReasonCode.MANAGER_UNAVAILABLE,
                        now + retryDelayMs, now);
            }
            selectedIds.add(manager.armadaAccountId());

            int stationCount = value(setting.getInitialStationCount());
            List<ProtocolAccountRef> stations = selectStations(
                    setting.getStationGroupId(), stationCount, selectedIds);
            if (stations.size() < stationCount) {
                return defer(candidate, PullTaskExecutionReasonCode.STATION_UNAVAILABLE,
                        now + retryDelayMs, now);
            }

            String operationId = "ptgc:" + candidate.getTenantId() + ":" + candidate.getId();
            String subject = groupSubject(candidate, groupSetting);
            PullTaskGroupCreateTransition transition = transition(
                    candidate,
                    PullTaskExecutionStatus.EXECUTING.code(),
                    PullTaskExecutionStage.GROUP_CREATE.code(),
                    PullTaskGroupCreateStep.CREATE_GROUP.code(),
                    operationId,
                    value(candidate.getCreateAttemptCount()),
                    subject,
                    null, null, null, null,
                    null, null, null, now, now);
            if (persistence.executionMapper().transitionGroupCreate(transition) != 1) {
                return PullTaskExecutionDispatchResult.LOST;
            }

            insertRole(candidate, creator, PullTaskGroupAccountRole.PROMOTER, 1, null, now);
            insertRole(candidate, manager, PullTaskGroupAccountRole.MANAGER, 1,
                    PullTaskAccountEntryMode.GROUP_CREATE_INITIAL.code(), now);
            for (int index = 0; index < stations.size(); index++) {
                insertRole(candidate, stations.get(index), PullTaskGroupAccountRole.STATION,
                        index + 1, PullTaskAccountEntryMode.GROUP_CREATE_INITIAL.code(), now);
            }
            return PullTaskExecutionDispatchResult.ADVANCED;
        });
    }

    /** 步骤 2 调用前读取已冻结角色和协议身份。 */
    @Transactional(rollbackFor = Exception.class)
    public GroupCreatePreparation prepareCreate(
            PullTaskGroupExecution candidate,
            long now,
            long retryDelayMs) {
        return withTenant(candidate.getTenantId(), () -> {
            List<PullTaskGroupAccount> creators = roles(
                    candidate.getId(), PullTaskGroupAccountRole.PROMOTER);
            List<PullTaskGroupAccount> managers = roles(
                    candidate.getId(), PullTaskGroupAccountRole.MANAGER);
            if (creators.size() != 1 || managers.size() != 1
                    || !hasText(candidate.getCreateOperationId())
                    || !hasText(candidate.getGroupSubject())) {
                return GroupCreatePreparation.completed(pauseInvalid(candidate, now));
            }
            PullTaskGroupAccount creator = creators.get(0);
            ProtocolAccountRef creatorRef = resources.accountLookup()
                    .findActiveProtocolRef(creator.getAccountId()).orElse(null);
            if (creatorRef == null) {
                return GroupCreatePreparation.completed(defer(
                        candidate, PullTaskExecutionReasonCode.GROUP_CREATOR_UNAVAILABLE,
                        now + retryDelayMs, now));
            }
            List<PullTaskGroupAccount> participants = new ArrayList<>();
            participants.add(managers.get(0));
            participants.addAll(roles(candidate.getId(), PullTaskGroupAccountRole.STATION));
            List<String> phones = participants.stream()
                    .map(PullTaskGroupAccount::getAccountPhone)
                    .filter(PullTaskGroupCreateTransactionService::hasText)
                    .distinct()
                    .toList();
            if (phones.isEmpty()) {
                return GroupCreatePreparation.completed(pauseInvalid(candidate, now));
            }
            GroupCreateCommand command = new GroupCreateCommand(
                    creatorRef, candidate.getGroupSubject(), phones, false,
                    candidate.getCreateOperationId());
            return GroupCreatePreparation.ready(command);
        });
    }

    /** 建群成功后原子保存 JID，并只把明确成功的初始成员标为在群。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult completeCreate(
            PullTaskGroupExecution candidate,
            GroupCreateResult result,
            long nextRunAt,
            long now) {
        if (result == null || !hasText(result.groupJid())) {
            return haltUnconfirmed(candidate, "协议返回缺少群 JID", now);
        }
        return withTenant(candidate.getTenantId(), () -> {
            PullTaskGroupCreateTransition transition = transition(
                    candidate,
                    PullTaskExecutionStatus.EXECUTING.code(),
                    PullTaskExecutionStage.GROUP_CREATE.code(),
                    PullTaskGroupCreateStep.APPLY_PROFILE.code(),
                    null, null, null,
                    result.groupJid().trim(), null, null, null,
                    null, null, null, nextRunAt, now);
            if (persistence.executionMapper().transitionGroupCreate(transition) != 1) {
                return PullTaskExecutionDispatchResult.LOST;
            }
            markCreatorsInGroup(candidate.getId(), now);
            markSuccessfulParticipants(candidate.getId(), result.results(), now);
            return PullTaskExecutionDispatchResult.ADVANCED;
        });
    }

    /** 按 ADR-0013 区分明确未创建与结果未知。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult failCreate(
            PullTaskGroupExecution candidate,
            ProtocolException failure,
            long retryDelayMs,
            long now) {
        if (failure != null && DEFINITELY_NOT_CREATED.contains(failure.errorCode())) {
            return withTenant(candidate.getTenantId(), () -> {
                int attempts = Math.addExact(value(candidate.getCreateAttemptCount()), 1);
                PullTaskExecutionReasonCode reason = PullTaskExecutionReasonCode.GROUP_CREATE_FAILED;
                PullTaskGroupCreateTransition transition = transition(
                        candidate,
                        PullTaskExecutionStatus.EXECUTING.code(),
                        PullTaskExecutionStage.GROUP_CREATE.code(),
                        PullTaskGroupCreateStep.CREATE_GROUP.code(),
                        null, attempts, null, null, null, null, null,
                        null, reason.name(), compact(failure), now + retryDelayMs, now);
                return persistence.executionMapper().transitionGroupCreate(transition) == 1
                        ? PullTaskExecutionDispatchResult.DEFERRED
                        : PullTaskExecutionDispatchResult.LOST;
            });
        }
        return haltUnconfirmed(candidate, compact(failure), now);
    }

    /** 结果可能已经成功时自动暂停，保留同一 operationId，绝不自动重建。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult haltUnconfirmed(
            PullTaskGroupExecution candidate,
            String detail,
            long now) {
        return withTenant(candidate.getTenantId(), () -> {
            PullTaskExecutionReasonCode reason =
                    PullTaskExecutionReasonCode.GROUP_CREATE_RESULT_UNCONFIRMED;
            PullTaskGroupCreateTransition transition = transition(
                    candidate,
                    PullTaskExecutionStatus.EXECUTING.code(),
                    PullTaskExecutionStage.GROUP_CREATE.code(),
                    PullTaskGroupCreateStep.CREATE_GROUP.code(),
                    null, null, null, null, null, null, null,
                    1, reason.name(), appendDetail(reason.message(), detail), now, now);
            return persistence.executionMapper().transitionGroupCreate(transition) == 1
                    ? PullTaskExecutionDispatchResult.DEFERRED
                    : PullTaskExecutionDispatchResult.LOST;
        });
    }

    /** 步骤 4/6：复用既有群资料命令，并推进到下一个持久化步骤。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult applyProfile(
            PullTaskGroupExecution candidate,
            PullTaskGroupCreateStep targetStep,
            long nextRunAt,
            long now) {
        return withTenant(candidate.getTenantId(), () -> {
            PullTaskGroupCreateTransition transition = transition(
                    candidate,
                    PullTaskExecutionStatus.EXECUTING.code(),
                    PullTaskExecutionStage.GROUP_CREATE.code(),
                    targetStep.code(),
                    null, null, null, null, null, null, null,
                    null, null, null, nextRunAt, now);
            if (persistence.executionMapper().transitionGroupCreate(transition) != 1) {
                return PullTaskExecutionDispatchResult.LOST;
            }
            resources.profileDispatcher().dispatchIfDue(
                    candidate, PullTaskGroupSettingTiming.BEFORE_PULL, now);
            return PullTaskExecutionDispatchResult.ADVANCED;
        });
    }

    /** 步骤 5 调用前解析固定建群人协议身份。 */
    @Transactional(rollbackFor = Exception.class)
    public InvitePreparation prepareInvite(
            PullTaskGroupExecution candidate,
            long retryDelayMs,
            long now) {
        return withTenant(candidate.getTenantId(), () -> {
            List<PullTaskGroupAccount> creators = roles(
                    candidate.getId(), PullTaskGroupAccountRole.PROMOTER);
            ProtocolAccountRef creator = creators.size() == 1
                    ? resources.accountLookup().findActiveProtocolRef(
                            creators.get(0).getAccountId()).orElse(null)
                    : null;
            if (creator == null || !hasText(candidate.getGroupJid())) {
                return InvitePreparation.completed(defer(
                        candidate, PullTaskExecutionReasonCode.GROUP_CREATOR_UNAVAILABLE,
                        now + retryDelayMs, now));
            }
            return InvitePreparation.ready(creator);
        });
    }

    /** 邀请链接读取成功后回填链接三元组并推进。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult completeInvite(
            PullTaskGroupExecution candidate,
            GroupInviteResult result,
            long nextRunAt,
            long now) {
        String normalized = normalizeInvite(result);
        if (normalized == null) {
            return deferInvite(
                    candidate,
                    "协议返回缺少有效邀请链接",
                    Math.max(0L, nextRunAt - now),
                    now);
        }
        String inviteCode = normalized.substring(normalized.lastIndexOf('/') + 1);
        return withTenant(candidate.getTenantId(), () -> {
            PullTaskGroupCreateTransition transition = transition(
                    candidate,
                    PullTaskExecutionStatus.EXECUTING.code(),
                    PullTaskExecutionStage.GROUP_CREATE.code(),
                    PullTaskGroupCreateStep.APPLY_BEFORE_PULL_SETTINGS.code(),
                    null, null, null, null, normalized, inviteCode, null,
                    null, null, null, nextRunAt, now);
            return persistence.executionMapper().transitionGroupCreate(transition) == 1
                    ? PullTaskExecutionDispatchResult.ADVANCED
                    : PullTaskExecutionDispatchResult.LOST;
        });
    }

    /** 邀请链接读取失败是安全可重试的查询，不改变已创建群事实。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult deferInvite(
            PullTaskGroupExecution candidate,
            String detail,
            long retryDelayMs,
            long now) {
        return withTenant(candidate.getTenantId(), () -> {
            PullTaskExecutionReasonCode reason =
                    PullTaskExecutionReasonCode.GROUP_INVITE_LINK_UNAVAILABLE;
            PullTaskGroupCreateTransition transition = transition(
                    candidate,
                    PullTaskExecutionStatus.EXECUTING.code(),
                    PullTaskExecutionStage.GROUP_CREATE.code(),
                    PullTaskGroupCreateStep.CAPTURE_INVITE_LINK.code(),
                    null, null, null, null, null, null, null,
                    null, reason.name(), appendDetail(reason.message(), detail),
                    now + retryDelayMs, now);
            return persistence.executionMapper().transitionGroupCreate(transition) == 1
                    ? PullTaskExecutionDispatchResult.DEFERRED
                    : PullTaskExecutionDispatchResult.LOST;
        });
    }

    /** 步骤 7：登记统一群入口和已确认成员，完成后衔接 MANAGER_JOIN。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult registerGroup(
            PullTaskGroupExecution candidate,
            long now) {
        return withTenant(candidate.getTenantId(), () -> {
            List<PullTaskGroupAccount> creators = roles(
                    candidate.getId(), PullTaskGroupAccountRole.PROMOTER);
            if (creators.size() != 1 || !hasText(candidate.getGroupJid())
                    || !hasText(candidate.getNormalizedLink())) {
                return pauseInvalid(candidate, now);
            }
            PullTaskGroupAccount creator = creators.get(0);
            List<PullTaskGroupAccount> managers = roles(
                    candidate.getId(), PullTaskGroupAccountRole.MANAGER);
            List<PullTaskGroupAccount> stations = roles(
                    candidate.getId(), PullTaskGroupAccountRole.STATION);
            int memberCount = 1 + inGroupCount(managers) + inGroupCount(stations);
            Long groupLinkId = resources.groupRegistry().registerSelfBuiltGroup(
                    candidate.getGroupJid(), candidate.getGroupSubject(), creator.getAccountId(),
                    creator.getAccountPhone(), memberCount, now);
            registerMemberships(groupLinkId, candidate.getGroupJid(), managers, now);
            registerMemberships(groupLinkId, candidate.getGroupJid(), stations, now);

            PullTaskGroupCreateTransition transition = transition(
                    candidate,
                    PullTaskExecutionStatus.EXECUTING.code(),
                    PullTaskExecutionStage.MANAGER_JOIN.code(),
                    PullTaskGroupCreateStep.REGISTER_GROUP.code(),
                    null, null, null, null, null, null, groupLinkId,
                    null, null, null, 0L, now);
            if (persistence.executionMapper().transitionGroupCreate(transition) != 1) {
                throw new IllegalStateException("自建群已登记但执行行检查点发生并发变化");
            }
            return PullTaskExecutionDispatchResult.ADVANCED;
        });
    }

    private PullTaskExecutionDispatchResult pauseInvalid(
            PullTaskGroupExecution candidate,
            long now) {
        PullTaskExecutionReasonCode reason =
                PullTaskExecutionReasonCode.GROUP_CREATE_CONFIGURATION_INVALID;
        PullTaskGroupCreateTransition transition = transition(
                candidate,
                PullTaskExecutionStatus.EXECUTING.code(),
                PullTaskExecutionStage.GROUP_CREATE.code(),
                step(candidate).code(),
                null, null, null, null, null, null, null,
                1, reason.name(), reason.message(), now, now);
        return persistence.executionMapper().transitionGroupCreate(transition) == 1
                ? PullTaskExecutionDispatchResult.DEFERRED
                : PullTaskExecutionDispatchResult.LOST;
    }

    private PullTaskExecutionDispatchResult defer(
            PullTaskGroupExecution candidate,
            PullTaskExecutionReasonCode reason,
            long nextRunAt,
            long now) {
        PullTaskGroupCreateTransition transition = transition(
                candidate,
                PullTaskExecutionStatus.EXECUTING.code(),
                PullTaskExecutionStage.GROUP_CREATE.code(),
                step(candidate).code(),
                null, null, null, null, null, null, null,
                null, reason.name(), reason.message(), nextRunAt, now);
        return persistence.executionMapper().transitionGroupCreate(transition) == 1
                ? PullTaskExecutionDispatchResult.DEFERRED
                : PullTaskExecutionDispatchResult.LOST;
    }

    private void insertRole(
            PullTaskGroupExecution candidate,
            ProtocolAccountRef account,
            PullTaskGroupAccountRole role,
            int roleSeq,
            Integer entryMode,
            long now) {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(candidate.getTaskId());
        row.setGroupExecutionId(candidate.getId());
        row.setAccountId(account.armadaAccountId());
        row.setAccountPhone(account.wsPhone());
        row.setRoleType(role.code());
        row.setRoleSeq(roleSeq);
        row.setSourceType(INITIAL_SOURCE);
        row.setSelectionMode(PullTaskSelectionMode.AUTOMATIC.code());
        row.setEntryMode(entryMode);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        if (persistence.accountMapper().insert(row) != 1 || row.getId() == null) {
            throw new IllegalStateException("建群角色行写入失败 role=" + role);
        }
    }

    private List<ProtocolAccountRef> online(Long groupId) {
        if (groupId == null) {
            return List.of();
        }
        List<ProtocolAccountRef> rows =
                resources.accountLookup().findOnlineNormalStrictByGroupId(groupId);
        return rows == null ? List.of() : rows.stream()
                .filter(Objects::nonNull)
                .filter(row -> row.armadaAccountId() != null)
                .toList();
    }

    private static ProtocolAccountRef selectStable(
            List<ProtocolAccountRef> candidates,
            Integer seq,
            Set<Long> excluded) {
        List<ProtocolAccountRef> eligible = candidates.stream()
                .filter(row -> !excluded.contains(row.armadaAccountId()))
                .toList();
        if (eligible.isEmpty()) {
            return null;
        }
        int index = Math.floorMod(value(seq) - 1, eligible.size());
        return eligible.get(index);
    }

    private List<ProtocolAccountRef> selectStations(
            Long groupId,
            int count,
            Set<Long> excluded) {
        if (count == 0) {
            return List.of();
        }
        return online(groupId).stream()
                .filter(row -> !excluded.contains(row.armadaAccountId()))
                .limit(count)
                .toList();
    }

    private List<PullTaskGroupAccount> roles(
            long executionId,
            PullTaskGroupAccountRole role) {
        List<PullTaskGroupAccount> rows = persistence.accountMapper()
                .selectByExecutionAndRole(executionId, role.code());
        return rows == null ? List.of() : rows;
    }

    private void markCreatorsInGroup(long executionId, long now) {
        for (PullTaskGroupAccount creator : roles(
                executionId, PullTaskGroupAccountRole.PROMOTER)) {
            persistence.accountMapper().updateMembership(
                    creator.getId(), PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), now, now);
        }
    }

    private void markSuccessfulParticipants(
            long executionId,
            List<GroupCreateParticipantResult> results,
            long now) {
        Set<String> succeeded = successfulJids(results);
        for (PullTaskGroupAccountRole role : List.of(
                PullTaskGroupAccountRole.MANAGER, PullTaskGroupAccountRole.STATION)) {
            for (PullTaskGroupAccount account : roles(executionId, role)) {
                String jid = userJid(account.getAccountPhone());
                if (jid != null && succeeded.contains(jid)) {
                    persistence.accountMapper().updateMembership(
                            account.getId(), PullTaskGroupAccountMembershipStatus.IN_GROUP.code(),
                            now, now);
                }
            }
        }
    }

    private static Set<String> successfulJids(List<GroupCreateParticipantResult> results) {
        if (results == null || results.isEmpty()) {
            return Set.of();
        }
        Set<String> succeeded = new LinkedHashSet<>();
        for (GroupCreateParticipantResult item : results) {
            if (item != null && (successCode(item.status()) || successCode(item.rawStatus()))) {
                String jid = userJid(item.jid());
                if (jid != null) {
                    succeeded.add(jid);
                }
            }
        }
        return succeeded;
    }

    private static boolean successCode(String value) {
        return value != null && PARTICIPANT_SUCCESS.contains(
                value.trim().toUpperCase(Locale.ROOT));
    }

    private static String userJid(String value) {
        try {
            return hasText(value) ? WhatsappJids.userJid(value) : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String groupSubject(
            PullTaskGroupExecution candidate,
            PullTaskStandardGroupSetting setting) {
        String configured = setting != null
                && Integer.valueOf(1).equals(setting.getGroupSettingEnabled())
                && !Integer.valueOf(1).equals(setting.getMaterialFilenameAsGroupName())
                ? trimToNull(setting.getGroupName()) : null;
        String subject = configured == null
                ? fileStem(candidate.getSourceFileName()) : configured;
        if (!hasText(subject)) {
            subject = "新群-" + value(candidate.getSeq());
        }
        String trimmed = subject.trim();
        return trimmed.length() <= GROUP_SUBJECT_MAX_LENGTH
                ? trimmed : trimmed.substring(0, GROUP_SUBJECT_MAX_LENGTH);
    }

    private static String fileStem(String fileName) {
        String value = trimToNull(fileName);
        if (value == null) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT).endsWith(".txt")
                ? value.substring(0, value.length() - 4) : value;
    }

    private static String normalizeInvite(GroupInviteResult result) {
        if (result == null) {
            return null;
        }
        String normalized = GroupLinkUrls.tryNormalize(result.inviteUrl()).orElse(null);
        if (normalized != null) {
            return normalized;
        }
        return hasText(result.inviteCode())
                ? GroupLinkUrls.tryNormalize(
                        "chat.whatsapp.com/" + result.inviteCode().trim()).orElse(null)
                : null;
    }

    private static int inGroupCount(List<PullTaskGroupAccount> rows) {
        return (int) rows.stream().filter(row -> Objects.equals(
                row.getMembershipStatus(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code())).count();
    }

    private void registerMemberships(
            Long groupLinkId,
            String groupJid,
            List<PullTaskGroupAccount> rows,
            long now) {
        rows.stream()
                .filter(row -> Objects.equals(row.getMembershipStatus(),
                        PullTaskGroupAccountMembershipStatus.IN_GROUP.code()))
                .forEach(row -> resources.groupRegistry().registerKnownMembership(
                        groupLinkId, groupJid, row.getAccountId(), false, now));
    }

    private static PullTaskGroupCreateTransition transition(
            PullTaskGroupExecution candidate,
            int targetExecutionStatus,
            int targetStage,
            int targetStep,
            String operationId,
            Integer attemptCount,
            String groupSubject,
            String groupJid,
            String normalizedLink,
            String inviteCode,
            Long groupLinkId,
            Integer manualPaused,
            String reasonCode,
            String reasonMessage,
            long nextRunAt,
            long now) {
        return new PullTaskGroupCreateTransition(
                candidate.getId(), candidate.getVersion(), candidate.getLockOwner(),
                PullTaskExecutionStatus.EXECUTING.code(),
                PullTaskExecutionStage.GROUP_CREATE.code(), step(candidate).code(),
                targetExecutionStatus, targetStage, targetStep,
                operationId, attemptCount, groupSubject, groupJid,
                normalizedLink, inviteCode, groupLinkId, manualPaused,
                reasonCode, reasonMessage, nextRunAt, now);
    }

    private static PullTaskGroupCreateStep step(PullTaskGroupExecution candidate) {
        return PullTaskGroupCreateStep.fromNullable(candidate.getCreateStep());
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static String compact(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }
        String value = hasText(throwable.getMessage())
                ? throwable.getMessage().trim() : throwable.getClass().getSimpleName();
        return value.length() <= 160 ? value : value.substring(0, 160);
    }

    private static String appendDetail(String message, String detail) {
        if (!hasText(detail)) {
            return message;
        }
        String combined = message + "：" + detail.trim();
        return combined.length() <= 255 ? combined : combined.substring(0, 255);
    }

    private static <T> T withTenant(Long tenantId, Supplier<T> action) {
        Long previous = TenantContext.get();
        try {
            TenantContext.set(tenantId);
            return action.get();
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    /** 建群调用前准备结果。 */
    public record GroupCreatePreparation(
            GroupCreateCommand command,
            PullTaskExecutionDispatchResult completedResult) {

        static GroupCreatePreparation ready(GroupCreateCommand command) {
            return new GroupCreatePreparation(command, null);
        }

        static GroupCreatePreparation completed(PullTaskExecutionDispatchResult result) {
            return new GroupCreatePreparation(null, result);
        }

        public boolean ready() {
            return command != null;
        }
    }

    /** 邀请链接读取前准备结果。 */
    public record InvitePreparation(
            ProtocolAccountRef creator,
            PullTaskExecutionDispatchResult completedResult) {

        static InvitePreparation ready(ProtocolAccountRef creator) {
            return new InvitePreparation(creator, null);
        }

        static InvitePreparation completed(PullTaskExecutionDispatchResult result) {
            return new InvitePreparation(null, result);
        }

        public boolean ready() {
            return creator != null;
        }
    }
}
