package com.armada.group.normalcreation.service.impl;

import com.armada.account.service.AccountService;
import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.service.AccountStateEventService;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.normalcreation.mapper.NormalGroupCreationMapper;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.SecondaryAdminWork;
import com.armada.group.normalcreation.model.enums.NormalGroupCreationErrorMessage;
import com.armada.group.normalcreation.support.NormalGroupCreationParticipantEligibility;
import com.armada.group.normalcreation.support.NormalGroupCreationSubject;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.group.service.GroupLinkService;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.platform.kafka.consumer.group.ProtocolNormalGroupCreationResultReportedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolNormalGroupCreationResultReportedSink;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 由统一协议结果 Topic 驱动的新建普群状态机。 */
@Service
public class NormalGroupCreationProtocolResultService
        implements ProtocolNormalGroupCreationResultReportedSink {

    private static final Logger log =
            LoggerFactory.getLogger(NormalGroupCreationProtocolResultService.class);
    private static final String ACCOUNT_NOT_ONLINE = "ACCOUNT_NOT_ONLINE";
    private static final String ACCOUNT_OFFLINE_STATE = "OFFLINE";
    private static final String ACCOUNT_OFFLINE_SEMANTIC = "NORMAL_GROUP_ACCOUNT_NOT_ONLINE";
    private static final String NORMAL_GROUP_CREATION_SOURCE = "normal_group_creation";

    private final NormalGroupCreationMapper mapper;
    private final NormalGroupCreationCommandDispatcher commandDispatcher;
    private final GroupParticipantPort participantPort;
    private final GroupLinkRegistryService groupLinkRegistryService;
    private final GroupLinkService groupLinkService;
    private final GroupMetadataSyncTaskService metadataSyncTaskService;
    private final AccountService accountService;
    private final AccountStateEventService accountStateEventService;
    private final AccountStateMapper accountStateMapper;

    public NormalGroupCreationProtocolResultService(
            NormalGroupCreationMapper mapper,
            NormalGroupCreationCommandDispatcher commandDispatcher,
            GroupParticipantPort participantPort,
            GroupLinkRegistryService groupLinkRegistryService,
            GroupLinkService groupLinkService,
            GroupMetadataSyncTaskService metadataSyncTaskService,
            AccountService accountService,
            AccountStateEventService accountStateEventService,
            AccountStateMapper accountStateMapper) {
        this.mapper = mapper;
        this.commandDispatcher = commandDispatcher;
        this.participantPort = participantPort;
        this.groupLinkRegistryService = groupLinkRegistryService;
        this.groupLinkService = groupLinkService;
        this.metadataSyncTaskService = metadataSyncTaskService;
        this.accountService = accountService;
        this.accountStateEventService = accountStateEventService;
        this.accountStateMapper = accountStateMapper;
    }

    /**
     * 串行锁定计划群并应用最终结果；状态推进与下一条 Outbox 命令同事务提交。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleNormalGroupCreationResult(
            ProtocolNormalGroupCreationResultReportedEvent event) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(event.tenantId());
        try {
            ItemWork item = mapper.selectItemWorkForUpdate(event.tenantId(), event.itemId());
            if (item == null
                    || !Objects.equals(item.tenantId(), event.tenantId())
                    || !Objects.equals(item.taskId(), event.taskId())) {
                throw validation("新建普群结果关联任务不存在或租户不一致");
            }
            String expectedStep = expectedStep(event.action());
            if (!expectedStep.equals(item.currentStep()) || terminal(item.status())) {
                log.info("忽略重复或迟到的新建普群结果 tenantId={} itemId={} action={} commandId={}",
                        event.tenantId(), event.itemId(), event.action(), event.commandId());
                return;
            }
            ContactTarget contactTarget = validateActorAndCommand(item, event);
            if ("CONTACT_PREPARE".equals(event.action())) {
                if (contactTarget != null) {
                    contactSettled(item, contactTarget, event);
                }
                return;
            }
            if (!"SUCCESS".equals(event.outcome())) {
                applyFailure(item, event, expectedStep);
                return;
            }
            switch (event.action()) {
                case "GROUP_CREATE" -> groupCreated(item, event);
                case "GROUP_SETTINGS_APPLY" -> settingsApplied(item, event);
                case "GROUP_LEAVE" -> complete(event, "LEAVING_GROUP", "SUCCESS");
                default -> throw validation("新建普群结果 action 非法");
            }
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private ContactTarget validateActorAndCommand(
            ItemWork item,
            ProtocolNormalGroupCreationResultReportedEvent event) {
        if ("CONTACT_PREPARE".equals(event.action())) {
            return validateContactActor(item, event);
        }
        String expectedCommandId = switch (event.action()) {
            case "GROUP_CREATE" -> item.createCommandId();
            case "GROUP_SETTINGS_APPLY" -> item.settingsCommandId();
            case "GROUP_LEAVE" -> item.leaveCommandId();
            default -> null;
        };
        requireActor(event, expectedCommandId, item.creatorAccountId(),
                item.creatorProtocolAccountId(), item.creatorProtocolBackend());
        return null;
    }

    private ContactTarget validateContactActor(
            ItemWork item,
            ProtocolNormalGroupCreationResultReportedEvent event) {
        MemberWork member = mapper.selectMemberWorkForUpdate(
                event.tenantId(), item.id(), event.memberId());
        String memberDirection = member == null ? null
                : Objects.equals(event.commandId(), member.creatorSaveCommandId())
                ? "CREATOR_SAVE_MEMBER"
                : Objects.equals(event.commandId(), member.memberSaveCommandId())
                ? "MEMBER_SAVE_CREATOR" : null;
        if (memberDirection != null) {
            requireProtocolDirection(event.direction(), memberDirection);
            boolean creatorDirection = "CREATOR_SAVE_MEMBER".equals(memberDirection);
            requireActor(
                    event,
                    event.commandId(),
                    creatorDirection ? item.creatorAccountId() : member.memberAccountId(),
                    creatorDirection
                            ? item.creatorProtocolAccountId() : member.memberProtocolAccountId(),
                    creatorDirection
                            ? item.creatorProtocolBackend() : member.memberProtocolBackend());
            return new ContactTarget(member, null, memberDirection);
        }
        SecondaryAdminWork secondary = mapper.selectSecondaryAdminWorkForUpdate(
                event.tenantId(), item.id(), event.memberId());
        if (secondary == null && member == null) {
            throw validation("联系人准备结果关联普通成员或次管理员不存在");
        }
        if (secondary == null) {
            logReplacedContactResult(event);
            return null;
        }
        String internalDirection = Objects.equals(
                event.commandId(), secondary.creatorSaveCommandId())
                ? "CREATOR_SAVE_SECONDARY"
                : Objects.equals(event.commandId(), secondary.secondarySaveCreatorCommandId())
                ? "SECONDARY_SAVE_CREATOR"
                : Objects.equals(event.commandId(), secondary.secondarySaveAnchorCommandId())
                ? "SECONDARY_SAVE_ANCHOR"
                : Objects.equals(event.commandId(), secondary.anchorSaveSecondaryCommandId())
                ? "ANCHOR_SAVE_SECONDARY" : null;
        if (internalDirection == null) {
            logReplacedContactResult(event);
            return null;
        }
        requireProtocolDirection(event.direction(), internalDirection);
        Long expectedAccountId;
        String expectedProtocolAccountId;
        String expectedBackend;
        switch (internalDirection) {
            case "CREATOR_SAVE_SECONDARY" -> {
                expectedAccountId = item.creatorAccountId();
                expectedProtocolAccountId = item.creatorProtocolAccountId();
                expectedBackend = item.creatorProtocolBackend();
            }
            case "SECONDARY_SAVE_CREATOR" -> {
                expectedAccountId = secondary.secondaryAdminAccountId();
                expectedProtocolAccountId = secondary.secondaryAdminProtocolAccountId();
                expectedBackend = secondary.secondaryAdminProtocolBackend();
            }
            case "SECONDARY_SAVE_ANCHOR" -> {
                expectedAccountId = secondary.secondaryAdminAccountId();
                expectedProtocolAccountId = secondary.secondaryAdminProtocolAccountId();
                expectedBackend = secondary.secondaryAdminProtocolBackend();
            }
            case "ANCHOR_SAVE_SECONDARY" -> {
                expectedAccountId = secondary.anchorMemberAccountId();
                expectedProtocolAccountId = secondary.anchorMemberProtocolAccountId();
                expectedBackend = secondary.anchorMemberProtocolBackend();
            }
            default -> throw validation("新建普群联系人准备方向非法");
        }
        requireActor(event, event.commandId(), expectedAccountId,
                expectedProtocolAccountId, expectedBackend);
        return new ContactTarget(null, secondary, internalDirection);
    }

    private static void requireProtocolDirection(
            String protocolDirection,
            String internalDirection) {
        String expected = switch (internalDirection) {
            case "CREATOR_SAVE_MEMBER", "CREATOR_SAVE_SECONDARY", "SECONDARY_SAVE_ANCHOR" ->
                    "CREATOR_SAVE_MEMBER";
            case "MEMBER_SAVE_CREATOR", "SECONDARY_SAVE_CREATOR", "ANCHOR_SAVE_SECONDARY" ->
                    "MEMBER_SAVE_CREATOR";
            default -> throw validation("新建普群联系人准备方向非法");
        };
        if (!Objects.equals(protocolDirection, expected)) {
            throw validation("新建普群联系人结果方向与命令不匹配");
        }
    }

    private static void logReplacedContactResult(
            ProtocolNormalGroupCreationResultReportedEvent event) {
        log.info("忽略已被替换的新建普群联系人结果 tenantId={} itemId={} memberId={} "
                        + "protocolDirection={} commandId={}",
                event.tenantId(), event.itemId(), event.memberId(),
                event.direction(), event.commandId());
    }

    private static void requireActor(
            ProtocolNormalGroupCreationResultReportedEvent event,
            String expectedCommandId,
            Long expectedAccountId,
            String expectedProtocolAccountId,
            String expectedBackend) {
        if (!Objects.equals(event.commandId(), expectedCommandId)
                || !Objects.equals(event.accountId(), expectedAccountId)
                || !Objects.equals(event.protocolAccountId(), expectedProtocolAccountId)
                || !Objects.equals(event.protocolBackend(), expectedBackend)) {
            throw validation("新建普群结果 commandId、执行账号或协议后端不匹配");
        }
    }

    /**
     * 落定一个联系人方向的最终结果。
     *
     * <p>加好友是尽力而为的前置动作：SUCCESS、FAILED、UNKNOWN 都只写回成员行，不再让整条
     * 计划群失败。双向结果全部落定后照常下发建群命令，建群名单只保留与群主双向成功的账号。</p>
     */
    private void contactSettled(
            ItemWork item,
            ContactTarget target,
            ProtocolNormalGroupCreationResultReportedEvent event) {
        FailureDetails failure =
                "SUCCESS".equals(event.outcome())
                        ? null : failureDetails(event, target.internalDirection());
        long now = System.currentTimeMillis();
        int applied = target.member() != null
                ? mapper.applyContactResult(
                target.member().id(), target.internalDirection(),
                event.commandId(), event.outcome(),
                failure == null ? null : failure.code(),
                failure == null ? null : failure.message(), now)
                : mapper.applySecondaryContactResult(
                target.secondaryAdmin().id(), target.internalDirection(),
                event.commandId(), event.outcome(),
                failure == null ? null : failure.code(),
                failure == null ? null : failure.message(), now);
        if (applied == 0) {
            return;
        }
        if (failure != null) {
            log.info("新建普群加好友未成功，按可选项放行建群 tenantId={} itemId={} memberId={} "
                            + "direction={} outcome={} reasonCode={}",
                    event.tenantId(), event.itemId(), event.memberId(), event.direction(),
                    event.outcome(), event.reasonCode());
            reconcileOfflineActor(event, now);
        }
        if (mapper.countPendingContactDirections(item.id()) != 0) {
            return;
        }
        String createCommandId = commandDispatcher.enqueueCreatorAction(item, "GROUP_CREATE");
        if (mapper.startGroupCreate(item.id(), createCommandId, System.currentTimeMillis()) != 1) {
            throw unavailable("联系人方向全部落定后无法推进建群阶段");
        }
    }

    private void groupCreated(
            ItemWork item,
            ProtocolNormalGroupCreationResultReportedEvent event) {
        String finalSubject;
        try {
            finalSubject = NormalGroupCreationSubject.finalizeAfterCreate(
                    item.groupNameTemplate(), item.groupSubject(), event.groupJid());
        } catch (IllegalArgumentException ex) {
            throw validation("建群成功回执中的群 JID 无法生成自动群名");
        }
        long now = System.currentTimeMillis();
        mapper.markParticipantsCreated(item.id(), now);
        mapper.markSecondaryParticipantsCreated(item.id(), now);
        List<SecondaryAdminWork> secondaryAdmins = mapper.selectSecondaryAdminWorks(item.id())
                .stream()
                .filter(NormalGroupCreationParticipantEligibility
                        ::secondaryAdminHasMutualCreatorContact)
                .toList();
        PromotionFailure promotionFailure = promoteSecondaryAdmins(
                item, event.groupJid(), secondaryAdmins);
        if (promotionFailure != null) {
            // 先记录所有已入群账号的成功基线，再用逐成员失败目标覆盖，保留部分成功事实。
            mapper.markSecondaryAdminsPromoted(item.id(), now);
            mapper.markSecondaryAdminPromotionFailures(
                    item.id(), promotionFailure.failedPhones(), promotionFailure.code(),
                    promotionFailure.message(), now);
            if (mapper.failProtocolAction(
                    item.id(), "CREATING_GROUP", event.commandId(), "CREATED_PARTIAL",
                    promotionFailure.code(), promotionFailure.message(), event.groupJid(),
                    event.eventId() == null ? event.commandId() : event.eventId(), now) != 1) {
                throw unavailable("次管理员设置失败后无法保存计划群状态");
            }
            migrateCreator(item.creatorAccountId(), item.successMigrationGroupId());
            mapper.refreshTaskSummary(item.taskId(), now);
            return;
        }
        mapper.markSecondaryAdminsPromoted(item.id(), now);
        String settingsCommandId =
                commandDispatcher.enqueueCreatorAction(item, "GROUP_SETTINGS_APPLY");
        if (mapper.startGroupSettings(
                item.id(), event.commandId(), settingsCommandId, event.groupJid(),
                finalSubject, now) != 1) {
            throw unavailable("次管理员设置成功后无法推进群权限设置阶段");
        }
    }

    /** 使用冻结的创群账号调用现有 Web/Android 成员端口，不新增协议动作或自动重试。 */
    private PromotionFailure promoteSecondaryAdmins(
            ItemWork item,
            String groupJid,
            List<SecondaryAdminWork> secondaryAdmins) {
        if (secondaryAdmins.isEmpty()) {
            return null;
        }
        List<String> targetJids = secondaryAdmins.stream()
                .map(SecondaryAdminWork::secondaryAdminWsPhone)
                .map(WhatsappJids::userJid)
                .toList();
        GroupParticipantBatchResult result;
        try {
            result = participantPort.updateParticipants(
                    new ProtocolAccountRef(
                            item.creatorAccountId(),
                            ProtocolBackend.valueOf(item.creatorProtocolBackend()),
                            item.creatorProtocolAccountId(),
                            item.creatorWsPhone()),
                    groupJid,
                    targetJids,
                    GroupParticipantAction.PROMOTE);
        } catch (ProtocolException ex) {
            String message = "群已创建，但次管理员设置失败：执行群主账号 "
                    + item.creatorAccountId() + "，协议错误 " + ex.errorCode().name();
            log.warn("新建普群调用现有成员提权接口失败 tenantId={} taskId={} itemId={} "
                            + "creatorAccountId={} backend={} targetCount={} code={}",
                    item.tenantId(), item.taskId(), item.id(), item.creatorAccountId(),
                    item.creatorProtocolBackend(), secondaryAdmins.size(), ex.errorCode());
            return new PromotionFailure(
                    "SECONDARY_ADMIN_PROMOTION_FAILED",
                    secondaryAdmins.stream()
                            .map(SecondaryAdminWork::secondaryAdminWsPhone).toList(),
                    message);
        }
        List<GroupParticipantBatchResult.Item> resultItems = result == null
                || result.results() == null ? List.of() : result.results();
        Map<String, GroupParticipantBatchResult.Item> byJid = resultItems.stream()
                .filter(row -> row != null && row.jid() != null && !row.jid().isBlank())
                .collect(Collectors.toMap(
                        row -> WhatsappJids.userJid(row.jid()),
                        Function.identity(),
                        (left, right) -> left));
        List<SecondaryAdminWork> failed = secondaryAdmins.stream()
                .filter(row -> {
                    GroupParticipantBatchResult.Item detail = byJid.get(
                            WhatsappJids.userJid(row.secondaryAdminWsPhone()));
                    return detail == null || !"OK".equalsIgnoreCase(detail.status());
                })
                .toList();
        if (failed.isEmpty() && result != null && !result.partial()) {
            log.info("新建普群次管理员设置完成 tenantId={} taskId={} itemId={} "
                            + "creatorAccountId={} backend={} successCount={}",
                    item.tenantId(), item.taskId(), item.id(), item.creatorAccountId(),
                    item.creatorProtocolBackend(), secondaryAdmins.size());
            return null;
        }
        List<String> failedPhones = (failed.isEmpty() ? secondaryAdmins : failed).stream()
                .map(SecondaryAdminWork::secondaryAdminWsPhone)
                .toList();
        String details = (failed.isEmpty() ? secondaryAdmins : failed).stream()
                .map(row -> {
                    GroupParticipantBatchResult.Item detail = byJid.get(
                            WhatsappJids.userJid(row.secondaryAdminWsPhone()));
                    String status = detail == null
                            ? "MISSING" : Objects.toString(detail.status(), "UNKNOWN");
                    String rawStatus = detail == null
                            ? "无逐成员回执" : Objects.toString(detail.rawStatus(), "无原始状态");
                    return "账号 " + row.secondaryAdminAccountId() + "（协议状态 "
                            + status + "，原始状态 " + rawStatus + "）";
                })
                .collect(Collectors.joining("；"));
        String message = "群已创建，但次管理员设置失败：" + details;
        log.warn("新建普群次管理员设置存在失败 tenantId={} taskId={} itemId={} "
                        + "creatorAccountId={} backend={} targetCount={} failedCount={} partial={}",
                item.tenantId(), item.taskId(), item.id(), item.creatorAccountId(),
                item.creatorProtocolBackend(), secondaryAdmins.size(), failedPhones.size(),
                result != null && result.partial());
        return new PromotionFailure(
                "SECONDARY_ADMIN_PROMOTION_FAILED", failedPhones, message);
    }

    private void settingsApplied(
            ItemWork item,
            ProtocolNormalGroupCreationResultReportedEvent event) {
        if ("LEAVE".equals(item.creatorLeavePolicy())) {
            String leaveCommandId = commandDispatcher.enqueueCreatorAction(item, "GROUP_LEAVE");
            if (mapper.startGroupLeave(
                    item.id(), event.commandId(), leaveCommandId, System.currentTimeMillis()) != 1) {
                throw unavailable("权限成功后无法推进退群阶段");
            }
            return;
        }
        complete(event, "APPLYING_SETTINGS", "SKIPPED");
    }

    private void complete(
            ProtocolNormalGroupCreationResultReportedEvent event,
            String expectedStep,
            String leaveStatus) {
        ItemWork item = mapper.selectItemWork(event.itemId());
        List<MemberWork> members = mapper.selectMemberWorks(item.id());
        List<SecondaryAdminWork> secondaryAdmins = mapper.selectSecondaryAdminWorks(item.id());
        long now = System.currentTimeMillis();
        Long groupLinkId = groupLinkRegistryService.registerSelfBuiltGroup(
                item.groupJid(), item.groupSubject(), item.creatorAccountId(),
                item.creatorWsPhone(), members.size() + secondaryAdmins.size() + 1, now);
        if (mapper.updateGroupLink(item.id(), groupLinkId, now) != 1) {
            throw unavailable("群组列表入口回写失败");
        }
        if (item.folderId() != null) {
            groupLinkService.assignFolder(List.of(groupLinkId), item.folderId());
        }
        for (SecondaryAdminWork secondary : secondaryAdmins) {
            groupLinkRegistryService.registerKnownMembership(
                    groupLinkId, item.groupJid(), secondary.secondaryAdminAccountId(), true, now);
        }
        for (int index = 0; index < members.size(); index++) {
            MemberWork member = members.get(index);
            groupLinkRegistryService.registerKnownMembership(
                    groupLinkId, item.groupJid(), member.memberAccountId(),
                    secondaryAdmins.isEmpty() && "SUCCESS".equals(leaveStatus) && index == 0, now);
        }
        metadataSyncTaskService.enqueue(
                groupLinkId, GroupMetadataSyncTrigger.BASELINE_CAPTURED, now);
        if (mapper.completeProtocolFlow(
                item.id(), expectedStep, event.commandId(), leaveStatus,
                event.eventId() == null ? event.commandId() : event.eventId(), now) != 1) {
            throw unavailable("协议动作全部成功后无法完成明细");
        }
        migrateCreator(item.creatorAccountId(), item.successMigrationGroupId());
        mapper.refreshTaskSummary(item.taskId(), now);
    }

    private void applyFailure(
            ItemWork item,
            ProtocolNormalGroupCreationResultReportedEvent event,
            String expectedStep) {
        long now = System.currentTimeMillis();
        FailureDetails failure = failureDetails(event);
        boolean groupExists = (item.groupJid() != null && !item.groupJid().isBlank())
                || ("GROUP_CREATE".equals(event.action())
                && event.groupJid() != null && !event.groupJid().isBlank());
        String createdGroupJid = "GROUP_CREATE".equals(event.action())
                ? event.groupJid() : null;
        AccountFailureClassification accountFailure = classifyUnknownGroupCreate(event, groupExists);
        if (accountFailure != null) {
            failure = new FailureDetails(accountFailure.code(), accountFailure.message());
        }
        String status = "UNKNOWN".equals(event.outcome()) && "GROUP_CREATE".equals(event.action())
                && accountFailure == null
                ? "RESULT_UNKNOWN" : groupExists ? "CREATED_PARTIAL" : "FAILED";
        if (mapper.failProtocolAction(
                item.id(), expectedStep, event.commandId(), status,
                failure.code(), failure.message(),
                createdGroupJid,
                event.eventId() == null ? event.commandId() : event.eventId(), now) != 1) {
            return;
        }
        reconcileOfflineActor(event, now);
        migrateCreator(item.creatorAccountId(), groupExists
                ? item.successMigrationGroupId() : item.failedMigrationGroupId());
        mapper.refreshTaskSummary(item.taskId(), now);
    }

    /**
     * 建群回执未知时，用控端最近一次账号状态补充失败归因。
     *
     * <p>仅在没有群 JID 时执行归因；即使账号随后离线，也不覆盖已经确认存在的群，避免把真实
     * 的建群成功错误显示成失败。查询异常时保留 RESULT_UNKNOWN 的保守语义。</p>
     */
    private AccountFailureClassification classifyUnknownGroupCreate(
            ProtocolNormalGroupCreationResultReportedEvent event,
            boolean groupExists) {
        if (!"UNKNOWN".equals(event.outcome())
                || !"GROUP_CREATE".equals(event.action())
                || groupExists
                || event.accountId() == null) {
            return null;
        }
        final AccountState state;
        try {
            state = accountStateMapper.selectByAccountId(event.accountId());
        } catch (RuntimeException ex) {
            log.warn("查询建群账号状态失败，保留结果未知 tenantId={} itemId={} accountId={}",
                    event.tenantId(), event.itemId(), event.accountId(), ex);
            return null;
        }
        if (state == null) {
            return null;
        }
        if (abnormalAccountState(state.getAccountState())) {
            return new AccountFailureClassification(
                    "ACCOUNT_ABNORMAL_DURING_CREATE", "建群号状态异常，建群结果未确认");
        }
        if (Integer.valueOf(AccountLoginStateCode.OFFLINE).equals(state.getLoginState())) {
            return new AccountFailureClassification(
                    "ACCOUNT_OFFLINE_DURING_CREATE", "建群号离线，建群结果未确认");
        }
        return null;
    }

    private static boolean abnormalAccountState(Integer accountState) {
        return accountState != null
                && accountState != AccountStateCode.NORMAL
                && accountState != AccountStateCode.NEW;
    }

    private void reconcileOfflineActor(
            ProtocolNormalGroupCreationResultReportedEvent event,
            long receivedAt) {
        if (!ACCOUNT_NOT_ONLINE.equals(event.reasonCode())) {
            return;
        }
        long occurredAt = event.timestamp() > 0 ? event.timestamp() : receivedAt;
        accountStateEventService.applyStateChanged(new AccountStateChangedEvent(
                event.tenantId(),
                event.accountId(),
                event.protocolAccountId(),
                null,
                ACCOUNT_OFFLINE_STATE,
                occurredAt,
                ACCOUNT_OFFLINE_SEMANTIC,
                null,
                NORMAL_GROUP_CREATION_SOURCE,
                null));
    }

    private static FailureDetails failureDetails(
            ProtocolNormalGroupCreationResultReportedEvent event) {
        return failureDetails(event, event.direction());
    }

    private static FailureDetails failureDetails(
            ProtocolNormalGroupCreationResultReportedEvent event,
            String internalDirection) {
        if (!ACCOUNT_NOT_ONLINE.equals(event.reasonCode())) {
            return new FailureDetails(event.reasonCode(), safeMessage(event));
        }
        if ("CONTACT_PREPARE".equals(event.action())
                && List.of("MEMBER_SAVE_CREATOR", "SECONDARY_SAVE_CREATOR",
                        "SECONDARY_SAVE_ANCHOR", "ANCHOR_SAVE_SECONDARY")
                .contains(internalDirection)) {
            return new FailureDetails(
                    event.reasonCode(),
                    NormalGroupCreationErrorMessage.accountNotOnlineMessage(true));
        }
        return new FailureDetails(
                event.reasonCode(),
                NormalGroupCreationErrorMessage.accountNotOnlineMessage(false));
    }

    private void migrateCreator(Long accountId, Long targetGroupId) {
        if (targetGroupId != null) {
            accountService.migrateGroup(List.of(accountId), targetGroupId);
        }
    }

    private static String safeMessage(ProtocolNormalGroupCreationResultReportedEvent event) {
        String message = event.reasonMessage();
        if (message == null || message.isBlank()) {
            return "协议动作未成功，原因码：" + Objects.toString(event.reasonCode(), "UNKNOWN");
        }
        return message.length() <= 512 ? message : message.substring(0, 512);
    }

    private static String expectedStep(String action) {
        return switch (action) {
            case "CONTACT_PREPARE" -> "PREPARING_CONTACTS";
            case "GROUP_CREATE" -> "CREATING_GROUP";
            case "GROUP_SETTINGS_APPLY" -> "APPLYING_SETTINGS";
            case "GROUP_LEAVE" -> "LEAVING_GROUP";
            default -> throw validation("新建普群 action 非法");
        };
    }

    private static boolean terminal(String status) {
        return List.of("CREATED", "CREATED_PARTIAL", "FAILED", "RESULT_UNKNOWN").contains(status);
    }

    private static void restoreTenant(Long tenantId) {
        if (tenantId == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(tenantId);
        }
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private static BusinessException unavailable(String message) {
        return new BusinessException(ErrorCode.AUTH_SERVICE_UNAVAILABLE, message);
    }

    private record FailureDetails(String code, String message) {
    }

    private record AccountFailureClassification(String code, String message) {
    }

    private record ContactTarget(
            MemberWork member,
            SecondaryAdminWork secondaryAdmin,
            String internalDirection) {
    }

    private record PromotionFailure(String code, List<String> failedPhones, String message) {
    }
}
