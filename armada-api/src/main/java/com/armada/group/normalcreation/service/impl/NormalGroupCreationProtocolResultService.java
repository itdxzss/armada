package com.armada.group.normalcreation.service.impl;

import com.armada.account.service.AccountService;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.normalcreation.mapper.NormalGroupCreationMapper;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberWork;
import com.armada.group.normalcreation.support.NormalGroupCreationSubject;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.group.service.GroupLinkService;
import com.armada.group.service.GroupMetadataSyncTaskService;
import com.armada.platform.kafka.consumer.group.ProtocolNormalGroupCreationResultReportedEvent;
import com.armada.platform.kafka.consumer.group.ProtocolNormalGroupCreationResultReportedSink;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
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

    private final NormalGroupCreationMapper mapper;
    private final NormalGroupCreationCommandDispatcher commandDispatcher;
    private final GroupLinkRegistryService groupLinkRegistryService;
    private final GroupLinkService groupLinkService;
    private final GroupMetadataSyncTaskService metadataSyncTaskService;
    private final AccountService accountService;

    public NormalGroupCreationProtocolResultService(
            NormalGroupCreationMapper mapper,
            NormalGroupCreationCommandDispatcher commandDispatcher,
            GroupLinkRegistryService groupLinkRegistryService,
            GroupLinkService groupLinkService,
            GroupMetadataSyncTaskService metadataSyncTaskService,
            AccountService accountService) {
        this.mapper = mapper;
        this.commandDispatcher = commandDispatcher;
        this.groupLinkRegistryService = groupLinkRegistryService;
        this.groupLinkService = groupLinkService;
        this.metadataSyncTaskService = metadataSyncTaskService;
        this.accountService = accountService;
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
            MemberWork member = validateActorAndCommand(item, event);
            if ("CONTACT_PREPARE".equals(event.action()) && member == null) {
                return;
            }
            if (!"SUCCESS".equals(event.outcome())) {
                applyFailure(item, member, event, expectedStep);
                return;
            }
            switch (event.action()) {
                case "CONTACT_PREPARE" -> contactPrepared(item, member, event);
                case "GROUP_CREATE" -> groupCreated(item, event);
                case "GROUP_SETTINGS_APPLY" -> settingsApplied(item, event);
                case "GROUP_LEAVE" -> complete(event, "LEAVING_GROUP", "SUCCESS");
                default -> throw validation("新建普群结果 action 非法");
            }
        } finally {
            restoreTenant(previousTenant);
        }
    }

    private MemberWork validateActorAndCommand(
            ItemWork item,
            ProtocolNormalGroupCreationResultReportedEvent event) {
        if ("CONTACT_PREPARE".equals(event.action())) {
            MemberWork member = mapper.selectMemberWorkForUpdate(
                    event.tenantId(), item.id(), event.memberId());
            if (member == null) {
                throw validation("联系人准备结果关联成员不存在");
            }
            boolean creatorDirection = "CREATOR_SAVE_MEMBER".equals(event.direction());
            String expectedCommandId = creatorDirection
                    ? member.creatorSaveCommandId() : member.memberSaveCommandId();
            Long expectedAccountId = creatorDirection
                    ? item.creatorAccountId() : member.memberAccountId();
            String expectedProtocolAccountId = creatorDirection
                    ? item.creatorProtocolAccountId() : member.memberProtocolAccountId();
            String expectedBackend = creatorDirection
                    ? item.creatorProtocolBackend() : member.memberProtocolBackend();
            if (!Objects.equals(event.commandId(), expectedCommandId)) {
                log.info("忽略已被重试替换的新建普群联系人结果 tenantId={} itemId={} memberId={} "
                                + "direction={} commandId={}",
                        event.tenantId(), event.itemId(), event.memberId(),
                        event.direction(), event.commandId());
                return null;
            }
            requireActor(event, expectedCommandId, expectedAccountId,
                    expectedProtocolAccountId, expectedBackend);
            return member;
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

    private void contactPrepared(
            ItemWork item,
            MemberWork member,
            ProtocolNormalGroupCreationResultReportedEvent event) {
        if (mapper.applyContactResult(
                member.id(), event.direction(), event.commandId(), "SUCCESS",
                null, null, System.currentTimeMillis()) == 0) {
            return;
        }
        if (mapper.countIncompleteContactDirections(item.id()) != 0) {
            return;
        }
        String createCommandId = commandDispatcher.enqueueCreatorAction(item, "GROUP_CREATE");
        if (mapper.startGroupCreate(item.id(), createCommandId, System.currentTimeMillis()) != 1) {
            throw unavailable("联系人完成后无法推进建群阶段");
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
        String settingsCommandId =
                commandDispatcher.enqueueCreatorAction(item, "GROUP_SETTINGS_APPLY");
        long now = System.currentTimeMillis();
        if (mapper.startGroupSettings(
                item.id(), event.commandId(), settingsCommandId, event.groupJid(),
                finalSubject, now) != 1) {
            throw unavailable("建群成功后无法推进权限阶段");
        }
        mapper.markParticipantsCreated(item.id(), now);
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
        long now = System.currentTimeMillis();
        Long groupLinkId = groupLinkRegistryService.registerSelfBuiltGroup(
                item.groupJid(), item.groupSubject(), item.creatorAccountId(),
                item.creatorWsPhone(), members.size() + 1, now);
        if (mapper.updateGroupLink(item.id(), groupLinkId, now) != 1) {
            throw unavailable("群组列表入口回写失败");
        }
        if (item.folderId() != null) {
            groupLinkService.assignFolder(List.of(groupLinkId), item.folderId());
        }
        for (int index = 0; index < members.size(); index++) {
            MemberWork member = members.get(index);
            groupLinkRegistryService.registerKnownMembership(
                    groupLinkId, item.groupJid(), member.memberAccountId(),
                    "SUCCESS".equals(leaveStatus) && index == 0, now);
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
            MemberWork member,
            ProtocolNormalGroupCreationResultReportedEvent event,
            String expectedStep) {
        long now = System.currentTimeMillis();
        if (member != null) {
            if (mapper.applyContactResult(
                    member.id(), event.direction(), event.commandId(), event.outcome(),
                    event.reasonCode(), safeMessage(event), now) != 1) {
                return;
            }
        }
        boolean groupExists = (item.groupJid() != null && !item.groupJid().isBlank())
                || ("GROUP_CREATE".equals(event.action())
                && event.groupJid() != null && !event.groupJid().isBlank());
        String createdGroupJid = "GROUP_CREATE".equals(event.action())
                ? event.groupJid() : null;
        String status = "UNKNOWN".equals(event.outcome()) && "GROUP_CREATE".equals(event.action())
                ? "RESULT_UNKNOWN" : groupExists ? "CREATED_PARTIAL" : "FAILED";
        if (mapper.failProtocolAction(
                item.id(), expectedStep, event.commandId(), status,
                event.reasonCode(), safeMessage(event),
                createdGroupJid,
                event.eventId() == null ? event.commandId() : event.eventId(), now) != 1) {
            return;
        }
        migrateCreator(item.creatorAccountId(), groupExists
                ? item.successMigrationGroupId() : item.failedMigrationGroupId());
        mapper.refreshTaskSummary(item.taskId(), now);
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
}
