package com.armada.task.scheduler;

import com.armada.group.service.GroupLinkUrls;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.command.ProtocolPullTaskGroupProfileCommandRequest;
import com.armada.platform.protocol.model.result.GroupCreateParticipantResult;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.enums.PullTaskActionStatus;
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
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 新群模式建群阶段的短事务与持久化检查点。 */
@Service
public class PullTaskGroupCreateTransactionService {

    private static final int INITIAL_SOURCE = 1;
    private static final int GROUP_SUBJECT_MAX_LENGTH = 100;
    private static final long PROFILE_RESULT_TIMEOUT_MS = 30_000L;
    private static final Set<String> PARTICIPANT_SUCCESS =
            Set.of("OK", "SUCCESS", "ALREADY_IN", "200");
    private static final Set<ProtocolErrorCode> DEFINITELY_NOT_CREATED = EnumSet.of(
            ProtocolErrorCode.BAD_REQUEST,
            ProtocolErrorCode.ACCOUNT_NOT_ONLINE,
            ProtocolErrorCode.UNSUPPORTED_BACKEND,
            ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED);

    private final PullTaskGroupCreatePersistence persistence;
    private final PullTaskGroupCreateResources resources;
    private final PullTaskCreatorDeletionTransactionService creatorDeletionTransactions;

    public PullTaskGroupCreateTransactionService(
            PullTaskGroupCreatePersistence persistence,
            PullTaskGroupCreateResources resources,
            PullTaskCreatorDeletionTransactionService creatorDeletionTransactions) {
        this.persistence = persistence;
        this.resources = resources;
        this.creatorDeletionTransactions = creatorDeletionTransactions;
    }

    /** 步骤 1：冻结建群角色和幂等键，沿用提交时的群名；历史任务在此冻结原名。 */
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
                    || setting.getManagerGroupId() == null
                    || !validProfileSetting(groupSetting, isSimplifiedNewGroup(candidate.getTaskId()))) {
                return pauseInvalid(candidate, now);
            }
            String subject = groupSubject(candidate, groupSetting);
            if (!hasText(subject) || subject.length() > GROUP_SUBJECT_MAX_LENGTH) {
                return pauseInvalid(candidate, now);
            }

            Set<Long> selectedIds = new LinkedHashSet<>();
            List<ProtocolAccountRef> creatorCandidates = online(setting.getCreatorGroupId());
            if (Integer.valueOf(1).equals(setting.getCreatorDeleteAfterTakeover())) {
                creatorCandidates = creatorCandidates.stream().filter(account -> account.backend()
                        == com.armada.platform.protocol.model.enums.ProtocolBackend.ANDROID).toList();
            }
            ProtocolAccountRef creator = selectStable(
                    creatorCandidates, candidate.getSeq(), selectedIds);
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
            boolean deleteCreator = Integer.valueOf(1).equals(setting.getCreatorDeleteAfterTakeover());
            if (deleteCreator) {
                Set<Long> otherRoles = new LinkedHashSet<>();
                otherRoles.add(manager.armadaAccountId());
                stations.forEach(station -> otherRoles.add(station.armadaAccountId()));
                creator = reserveCreator(candidate, creatorCandidates, otherRoles, operationId, now);
                if (creator == null) {
                    return defer(candidate, PullTaskExecutionReasonCode.GROUP_CREATOR_UNAVAILABLE,
                            now + retryDelayMs, now);
                }
            }
            if (persistence.executionMapper().transitionGroupCreate(transition) != 1) {
                if (deleteCreator) { throw new IllegalStateException("执行行租约变化，回滚一次性建群账号占用"); }
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
            if (creatorRef != null && !frozenCreatorMatches(candidate, creatorRef)) {
                return GroupCreatePreparation.completed(pauseInvalid(candidate, now));
            }
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

    /** 步骤 4/6：事务内提交资料命令；在途等待，结果返回后允许事务外读取真实群资料。 */
    @Transactional(rollbackFor = Exception.class)
    public ProfilePreparation prepareProfile(PullTaskGroupExecution candidate, long retryDelayMs, long now) {
        return withTenant(candidate.getTenantId(), () -> {
            PullTaskGroupExecution current = persistence.executionMapper().selectByIdForUpdate(candidate.getId());
            if (!currentProfileAttempt(current, candidate, now)) {
                return ProfilePreparation.completed(PullTaskExecutionDispatchResult.LOST);
            }
            PullTaskStandardGroupSetting setting = persistence.groupSettingMapper()
                    .selectByTaskId(candidate.getTaskId());
            if (!validProfileSetting(setting, isSimplifiedNewGroup(candidate.getTaskId()))
                    || !hasText(candidate.getGroupJid())
                    || !hasText(candidate.getGroupSubject())
                    || candidate.getGroupSubject().length() > GROUP_SUBJECT_MAX_LENGTH) {
                return ProfilePreparation.completed(pauseInvalid(candidate, now));
            }
            ProtocolAccountRef creator = onlineProfileCreator(candidate).orElse(null);
            if (creator == null) {
                return ProfilePreparation.completed(defer(candidate,
                        PullTaskExecutionReasonCode.GROUP_CREATOR_UNAVAILABLE, now + retryDelayMs, now));
            }
            resources.profileDispatcher().dispatchIfDue(candidate, PullTaskGroupSettingTiming.BEFORE_PULL, now);
            PullTaskAccountAction action = persistence.actionMapper().selectByExecutionAndType(
                    candidate.getId(), PullTaskAccountActionType.APPLY_GROUP_SETTINGS.code())
                    .stream().findFirst().orElse(null);
            if (action == null) {
                return ProfilePreparation.completed(defer(candidate,
                        PullTaskExecutionReasonCode.GROUP_CREATOR_UNAVAILABLE, now + retryDelayMs, now));
            }
            if (!hasText(action.getCommandId()) || !verifiableProfileAction(action)) {
                return ProfilePreparation.completed(pauseProfile(candidate, now));
            }
            long submittedAt = action.getSubmittedAt();
            if (Objects.equals(action.getActionStatus(), PullTaskActionStatus.SUBMITTED.code())
                    && now < submittedAt + PROFILE_RESULT_TIMEOUT_MS) {
                return ProfilePreparation.completed(defer(candidate,
                        PullTaskExecutionReasonCode.GROUP_PROFILE_UNCONFIRMED, now + retryDelayMs, now));
            }
            PullTaskGroupAccount actor = persistence.accountMapper().selectById(action.getActorGroupAccountId());
            if (actor == null || !Objects.equals(actor.getAccountId(), creator.armadaAccountId())) {
                return ProfilePreparation.completed(pauseProfile(candidate, now));
            }
            return new ProfilePreparation(creator, action.getId(), action.getCommandId(),
                    action.getAttemptNo(), candidate.getGroupSubject(), normalizedDescription(setting.getGroupDescription()),
                    submittedAt, null);
        });
    }

    /** 真实资料相符才推进；简化新群还须确认普通成员加人已开启、审批已关闭，未知字段不能放行。 */
    @Transactional(rollbackFor = Exception.class)
    public PullTaskExecutionDispatchResult completeProfile(
            PullTaskGroupExecution candidate, ProfilePreparation prepared,
            GroupMetadataResult metadata, long nextRunAt, long now) {
        return withTenant(candidate.getTenantId(), () -> {
            PullTaskGroupExecution current = persistence.executionMapper().selectByIdForUpdate(candidate.getId());
            if (!currentProfileAttempt(current, candidate, now)) {
                return PullTaskExecutionDispatchResult.LOST;
            }
            PullTaskAccountAction action = persistence.actionMapper().selectByCommandId(prepared.commandId());
            if (action == null || !verifiableProfileAction(action)
                    || !Objects.equals(action.getId(), prepared.actionId())
                    || !Objects.equals(action.getGroupExecutionId(), candidate.getId())
                    || Objects.equals(action.getActionStatus(), PullTaskActionStatus.CANCELED.code())
                    || !Objects.equals(action.getCommandId(), prepared.commandId())
                    || !Objects.equals(action.getAttemptNo(), prepared.attemptNo())) {
                return PullTaskExecutionDispatchResult.LOST;
            }
            boolean simplified = isSimplifiedNewGroup(candidate.getTaskId());
            PullTaskStandardGroupSetting setting = simplified
                    ? persistence.groupSettingMapper().selectByTaskId(candidate.getTaskId()) : null;
            boolean avatarRequired = simplified && setting != null && hasText(setting.getAvatarFileKey());
            boolean confirmed = metadata != null && !metadata.stateAbnormal()
                    && Objects.equals(candidate.getGroupJid(), metadata.groupJid())
                    && Objects.equals(prepared.subject(), metadata.subject())
                    && Objects.equals(prepared.description(), simplified
                            ? normalizedDescription(metadata.description()) : metadata.description())
                    && (!simplified || Boolean.TRUE.equals(metadata.memberAddMode())
                            && Boolean.FALSE.equals(metadata.joinApprovalMode()))
                    // 简化新群的全量及每次 UNKNOWN 补写都带冻结头像；只有该轮成功才证明头像完成。
                    && (!avatarRequired || Objects.equals(action.getActionStatus(), PullTaskActionStatus.SUCCESS.code()));
            if (!confirmed) {
                if (metadata == null || now < prepared.submittedAt() + PROFILE_RESULT_TIMEOUT_MS) {
                    return defer(candidate, PullTaskExecutionReasonCode.GROUP_PROFILE_UNCONFIRMED, nextRunAt, now);
                }
                if (!metadata.stateAbnormal() && Objects.equals(candidate.getGroupJid(), metadata.groupJid())
                        && Objects.equals(action.getActionStatus(), PullTaskActionStatus.UNKNOWN.code())) {
                    var repair = new ProtocolPullTaskGroupProfileCommandRequest.Repair(
                            !Objects.equals(prepared.subject(), metadata.subject()),
                            !Objects.equals(prepared.description(), simplified
                                    ? normalizedDescription(metadata.description()) : metadata.description()),
                            simplified && metadata.memberAddMode() != null && metadata.joinApprovalMode() != null
                                    && (Boolean.FALSE.equals(metadata.memberAddMode())
                                            || Boolean.TRUE.equals(metadata.joinApprovalMode())),
                            avatarRequired);
                    return repairProfile(candidate, action, repair, nextRunAt, now);
                }
                return pauseProfile(candidate, now);
            }
            PullTaskGroupCreateStep target = step(candidate) == PullTaskGroupCreateStep.APPLY_PROFILE
                    ? PullTaskGroupCreateStep.CAPTURE_INVITE_LINK : PullTaskGroupCreateStep.REGISTER_GROUP;
            PullTaskGroupCreateTransition transition = transition(candidate,
                    PullTaskExecutionStatus.EXECUTING.code(), PullTaskExecutionStage.GROUP_CREATE.code(),
                    target.code(), null, null, null, null, null, null, null,
                    null, null, null, nextRunAt, now).withProfileVerification(prepared.commandId());
            return persistence.executionMapper().transitionGroupCreate(transition) == 1
                    ? PullTaskExecutionDispatchResult.ADVANCED : PullTaskExecutionDispatchResult.LOST;
        });
    }

    private PullTaskExecutionDispatchResult repairProfile(
            PullTaskGroupExecution candidate, PullTaskAccountAction action,
            ProtocolPullTaskGroupProfileCommandRequest.Repair repair, long nextRunAt, long now) {
        ProtocolAccountRef creator = onlineProfileCreator(candidate).orElse(null);
        if (creator == null) {
            return defer(candidate, PullTaskExecutionReasonCode.GROUP_CREATOR_UNAVAILABLE, nextRunAt, now);
        }
        if (!resources.profileDispatcher().repairUnknownProfile(candidate, action, creator, repair, now)) {
            return pauseProfile(candidate, now);
        }
        PullTaskExecutionDispatchResult result = defer(candidate,
                PullTaskExecutionReasonCode.GROUP_PROFILE_UNCONFIRMED, nextRunAt, now);
        if (result == PullTaskExecutionDispatchResult.LOST) {
            throw new IllegalStateException("群资料补写后执行行状态写入不完整");
        }
        return result;
    }

    private static boolean currentProfileAttempt(
            PullTaskGroupExecution current, PullTaskGroupExecution candidate, long now) {
        return current != null && Objects.equals(current.getVersion(), candidate.getVersion())
                && Objects.equals(current.getLockOwner(), candidate.getLockOwner())
                && Objects.equals(current.getExecutionStatus(), PullTaskExecutionStatus.EXECUTING.code())
                && Objects.equals(current.getStage(), PullTaskExecutionStage.GROUP_CREATE.code())
                && Objects.equals(current.getCreateStep(), candidate.getCreateStep())
                && !Integer.valueOf(1).equals(current.getManualPaused())
                && current.getLockExpiresAt() != null && current.getLockExpiresAt() > now
                && Set.of(PullTaskGroupCreateStep.APPLY_PROFILE,
                        PullTaskGroupCreateStep.APPLY_BEFORE_PULL_SETTINGS).contains(step(current));
    }

    private Optional<ProtocolAccountRef> onlineProfileCreator(PullTaskGroupExecution candidate) {
        List<PullTaskGroupAccount> creators = roles(candidate.getId(), PullTaskGroupAccountRole.PROMOTER);
        if (creators.size() != 1) {
            return Optional.empty();
        }
        Long accountId = creators.get(0).getAccountId();
        return resources.accountLookup().findOnlineProtocolRefs(List.of(accountId)).stream()
                .filter(ref -> Objects.equals(ref.armadaAccountId(), accountId)).findFirst();
    }

    private PullTaskExecutionDispatchResult pauseProfile(PullTaskGroupExecution candidate, long now) {
        PullTaskExecutionReasonCode reason = PullTaskExecutionReasonCode.GROUP_PROFILE_VERIFICATION_FAILED;
        PullTaskGroupCreateTransition transition = transition(candidate,
                PullTaskExecutionStatus.EXECUTING.code(), PullTaskExecutionStage.GROUP_CREATE.code(),
                step(candidate).code(), null, null, null, null, null, null, null,
                1, reason.name(), reason.message(), now, now);
        return persistence.executionMapper().transitionGroupCreate(transition) == 1
                ? PullTaskExecutionDispatchResult.DEFERRED : PullTaskExecutionDispatchResult.LOST;
    }

    private static boolean validProfileSetting(PullTaskStandardGroupSetting setting, boolean simplified) {
        return setting != null && Integer.valueOf(1).equals(setting.getGroupSettingEnabled())
                && Integer.valueOf(PullTaskGroupSettingTiming.BEFORE_PULL.code()).equals(setting.getSettingTiming())
                && !Integer.valueOf(1).equals(setting.getMaterialFilenameAsGroupName())
                && hasText(setting.getGroupName()) && setting.getGroupName().trim().length() <= GROUP_SUBJECT_MAX_LENGTH
                && (simplified || hasText(setting.getGroupDescription()))
                && normalizedDescription(setting.getGroupDescription()).length() <= 1024;
    }

    private boolean isSimplifiedNewGroup(long taskId) {
        var task = persistence.taskMapper().selectLifecycle(taskId);
        return task != null && PullTaskCreationMode.fromNullable(task.getCreationMode()).isSimplifiedNewGroup();
    }

    private static String normalizedDescription(String description) {
        return description == null ? "" : description.trim();
    }

    private static boolean verifiableProfileAction(PullTaskAccountAction action) {
        return action.getActionStatus() != null && Set.of(PullTaskActionStatus.SUBMITTED.code(),
                PullTaskActionStatus.SUCCESS.code(), PullTaskActionStatus.FAILED.code(),
                PullTaskActionStatus.UNKNOWN.code()).contains(action.getActionStatus())
                && action.getAttemptNo() != null && action.getAttemptNo() > 0
                && action.getSubmittedAt() != null && action.getSubmittedAt() > 0;
    }

    /** 一次事务外资料核验绑定的不可变动作身份，防止旧查询覆盖新尝试。 */
    public record ProfilePreparation(
            ProtocolAccountRef account, Long actionId, String commandId, Integer attemptNo,
            String subject, String description, long submittedAt, PullTaskExecutionDispatchResult completedResult) {
        /** 只有已提交且可核验的动作才能读取群 metadata。 */
        public boolean ready() { return account != null; }
        /** 在途、资源缺失或配置错误不调用协议查询。 */
        public static ProfilePreparation completed(PullTaskExecutionDispatchResult result) {
            return new ProfilePreparation(null, null, null, null, null, null, 0L, result);
        }
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
            if (candidate.getProfileVerifiedAt() == null || candidate.getProfileVerifiedAt() <= 0
                    || !hasText(candidate.getProfileVerifiedCommandId())) {
                PullTaskExecutionReasonCode reason = PullTaskExecutionReasonCode.GROUP_PROFILE_UNCONFIRMED;
                PullTaskGroupCreateTransition verification = transition(candidate,
                        PullTaskExecutionStatus.EXECUTING.code(), PullTaskExecutionStage.GROUP_CREATE.code(),
                        PullTaskGroupCreateStep.APPLY_BEFORE_PULL_SETTINGS.code(),
                        null, null, null, null, null, null, null, null,
                        reason.name(), reason.message(), now, now);
                return persistence.executionMapper().transitionGroupCreate(verification) == 1
                        ? PullTaskExecutionDispatchResult.DEFERRED : PullTaskExecutionDispatchResult.LOST;
            }
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

    private boolean frozenCreatorMatches(PullTaskGroupExecution candidate, ProtocolAccountRef creator) {
        PullTaskStandardSetting setting = persistence.settingMapper().selectByTaskId(candidate.getTaskId());
        return setting == null || !Integer.valueOf(1).equals(setting.getCreatorDeleteAfterTakeover())
                || creatorDeletionTransactions.frozenCreatorMatches(candidate, creator);
    }

    private ProtocolAccountRef reserveCreator(PullTaskGroupExecution candidate,
            List<ProtocolAccountRef> candidates, Set<Long> excluded, String operationId, long now) {
        for (int offset = 0; offset < candidates.size(); offset++) {
            int index = Math.floorMod(value(candidate.getSeq()) - 1 + offset, candidates.size());
            ProtocolAccountRef creator = candidates.get(index);
            if (!excluded.contains(creator.armadaAccountId())
                    && creatorDeletionTransactions.reserve(candidate, creator, operationId, now)) {
                return creator;
            }
        }
        return null;
    }

    private List<ProtocolAccountRef> online(Long groupId) {
        if (groupId == null) {
            return List.of();
        }
        List<ProtocolAccountRef> rows =
                resources.accountLookup().findOnlinePullTaskAccountsStrictByGroupId(groupId);
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
            PullTaskGroupExecution candidate, PullTaskStandardGroupSetting setting) {
        // 新任务提交时已冻结带编号名称；历史已提交但未建群的任务沿用原名。
        return candidate.getGroupSubject() == null
                ? setting.getGroupName().trim() : candidate.getGroupSubject();
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
                reasonCode, reasonMessage, nextRunAt, now, null);
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
