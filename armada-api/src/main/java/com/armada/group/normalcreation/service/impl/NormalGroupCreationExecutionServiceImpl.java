package com.armada.group.normalcreation.service.impl;

import com.armada.account.service.AccountService;
import com.armada.group.normalcreation.mapper.NormalGroupCreationMapper;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.ItemWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberWork;
import com.armada.group.normalcreation.model.dto.NormalGroupCreationCommand;
import com.armada.group.normalcreation.service.NormalGroupCreationEventPublisher;
import com.armada.group.normalcreation.service.NormalGroupCreationExecutionService;
import com.armada.group.normalcreation.support.NormalGroupCreationAccountLock;
import com.armada.group.normalcreation.support.NormalGroupCreationLockLostException;
import com.armada.group.normalcreation.support.NormalGroupCreationRetryableException;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.group.service.GroupLinkService;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ContactSaveCommand;
import com.armada.platform.protocol.model.command.GroupCreateCommand;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupCreateParticipantResult;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.platform.protocol.port.ContactPort;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.platform.protocol.port.GroupLeavePort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.platform.protocol.port.GroupSettingsPort;
import com.armada.shared.tenant.TenantContext;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 新建普群三阶段编排；协议差异由既有 Routing Port 隔离。 */
@Service
public class NormalGroupCreationExecutionServiceImpl
        implements NormalGroupCreationExecutionService {

    private static final Logger log =
            LoggerFactory.getLogger(NormalGroupCreationExecutionServiceImpl.class);
    private static final long RETRY_DISPATCH_DELAY_MS = 60_000L;
    private static final Set<ProtocolErrorCode> DEFAULT_RETRYABLE_PROTOCOL_ERRORS = Set.of(
            ProtocolErrorCode.TIMEOUT,
            ProtocolErrorCode.NETWORK,
            ProtocolErrorCode.HTTP_ERROR,
            ProtocolErrorCode.NOT_OWNER,
            ProtocolErrorCode.ONLINE_LIMITED,
            ProtocolErrorCode.RECONNECT_LIMITED,
            ProtocolErrorCode.ACCOUNT_BUSY,
            ProtocolErrorCode.WORKER_BUSY,
            ProtocolErrorCode.RATE_LIMITED,
            ProtocolErrorCode.TEMPORARY_FAILURE,
            ProtocolErrorCode.ACCOUNT_NOT_ONLINE,
            ProtocolErrorCode.IDEMPOTENCY_STORE_UNAVAILABLE,
            ProtocolErrorCode.UNKNOWN);

    private final NormalGroupCreationMapper mapper;
    private final NormalGroupCreationEventPublisher publisher;
    private final ContactPort contactPort;
    private final GroupCreatePort groupCreatePort;
    private final GroupSettingsPort groupSettingsPort;
    private final FixedAccountGroupMetadataPort metadataPort;
    private final GroupParticipantPort participantPort;
    private final GroupLeavePort groupLeavePort;
    private final GroupLinkRegistryService groupLinkRegistryService;
    private final GroupLinkService groupLinkService;
    private final AccountService accountService;
    private final NormalGroupCreationAccountLock accountLock;
    private final int maxStageAttempts;

    public NormalGroupCreationExecutionServiceImpl(
            NormalGroupCreationMapper mapper,
            NormalGroupCreationEventPublisher publisher,
            ContactPort contactPort,
            GroupCreatePort groupCreatePort,
            GroupSettingsPort groupSettingsPort,
            FixedAccountGroupMetadataPort metadataPort,
            GroupParticipantPort participantPort,
            GroupLeavePort groupLeavePort,
            GroupLinkRegistryService groupLinkRegistryService,
            GroupLinkService groupLinkService,
            AccountService accountService,
            NormalGroupCreationAccountLock accountLock,
            @Value("${armada.normal-group-creation.max-stage-attempts:3}") int maxStageAttempts) {
        this.mapper = mapper;
        this.publisher = publisher;
        this.contactPort = contactPort;
        this.groupCreatePort = groupCreatePort;
        this.groupSettingsPort = groupSettingsPort;
        this.metadataPort = metadataPort;
        this.participantPort = participantPort;
        this.groupLeavePort = groupLeavePort;
        this.groupLinkRegistryService = groupLinkRegistryService;
        this.groupLinkService = groupLinkService;
        this.accountService = accountService;
        this.accountLock = accountLock;
        this.maxStageAttempts = Math.max(maxStageAttempts, 1);
    }

    @Override
    public void execute(NormalGroupCreationCommand command) {
        validateCommand(command);
        Long previousTenant = TenantContext.get();
        try {
            TenantContext.set(command.tenantId());
            ItemWork item = mapper.selectItemWork(command.itemId());
            if (item == null || !Objects.equals(item.taskId(), command.taskId())) {
                log.warn("新建普群消息找不到计划群 tenantId={} taskId={} itemId={} action={}",
                        command.tenantId(), command.taskId(), command.itemId(), command.action());
                return;
            }
            switch (command.action()) {
                case "PREPARE" -> prepare(command, item);
                case "CREATE" -> createGroup(command, item);
                case "POST_PROCESS" -> postProcess(command, item);
                default -> throw new IllegalArgumentException("未知新建普群 action");
            }
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    private void prepare(NormalGroupCreationCommand command, ItemWork item) {
        if (mapper.claimStage(item.id(), "PREPARING_CONTACTS", command.eventId(),
                "prepare", System.currentTimeMillis()) == 0) {
            return;
        }
        List<MemberWork> members = mapper.selectMemberWorks(item.id());
        ProtocolAccountRef creator = account(item.creatorAccountId(),
                item.creatorProtocolBackend(), item.creatorProtocolAccountId(), item.creatorWsPhone());
        try {
            for (MemberWork member : members) {
                ProtocolAccountRef memberAccount = account(member.memberAccountId(),
                        member.memberProtocolBackend(), member.memberProtocolAccountId(),
                        member.memberWsPhone());
                accountLock.runWithLocks(
                        command.tenantId(),
                        List.of(item.creatorAccountId(), member.memberAccountId()),
                        () -> {
                            contactPort.save(new ContactSaveCommand(
                                    creator, member.memberWsPhone(), member.memberWsPhone(),
                                    operation(item.id(), "creator-save-member-" + member.id())));
                            contactPort.save(new ContactSaveCommand(
                                    memberAccount, item.creatorWsPhone(), item.creatorWsPhone(),
                                    operation(item.id(), "member-save-creator-" + member.id())));
                        });
                mapper.updateContactStatus(
                        member.id(), "SUCCESS", "SUCCESS", null, null,
                        System.currentTimeMillis());
            }
            long now = System.currentTimeMillis();
            if (mapper.completePrepare(item.id(), command.eventId(), now) != 1) {
                throw new IllegalStateException("联系人准备完成后状态推进失败");
            }
            safePublish("CREATE", command, item.creatorAccountId());
        } catch (RuntimeException ex) {
            if (releaseForKafkaRetry(command, item, ex)) {
                throw ex;
            }
            fail(command, item, ex, false, false);
        }
    }

    private void createGroup(NormalGroupCreationCommand command, ItemWork item) {
        if (mapper.claimStage(item.id(), "CREATING_GROUP", command.eventId(),
                "create", System.currentTimeMillis()) == 0) {
            return;
        }
        ProtocolAccountRef creator = account(item.creatorAccountId(),
                item.creatorProtocolBackend(), item.creatorProtocolAccountId(), item.creatorWsPhone());
        List<MemberWork> members = mapper.selectMemberWorks(item.id());
        boolean groupCreated = false;
        boolean groupJidPersisted = false;
        try {
            GroupCreateResult result = accountLock.callWithLocks(
                    command.tenantId(), List.of(item.creatorAccountId()),
                    () -> groupCreatePort.create(new GroupCreateCommand(
                            creator,
                            item.groupSubject(),
                            members.stream().map(MemberWork::memberWsPhone).toList(),
                            !Boolean.TRUE.equals(item.sendMessagesAllowed()),
                            operation(item.id(), "create"))));
            if (result == null || result.groupJid() == null || result.groupJid().isBlank()) {
                throw new ProtocolException(
                        ProtocolErrorCode.GROUP_CREATE_RESULT_UNCONFIRMED,
                        "协议未返回新群 JID");
            }
            groupCreated = true;
            long now = System.currentTimeMillis();
            if (mapper.persistCreatedGroup(
                    item.id(), result.groupJid(), result.partial(), command.eventId(), now) != 1) {
                throw new IllegalStateException("新群 JID 持久化失败");
            }
            groupJidPersisted = true;
            updateParticipantResults(members, result);
            if (mapper.completeCreate(item.id(), command.eventId(),
                    System.currentTimeMillis()) != 1) {
                throw new IllegalStateException("逐成员回执完成后建群状态推进失败");
            }
            safePublish("POST_PROCESS", command, item.creatorAccountId());
        } catch (RuntimeException ex) {
            boolean resultUnknown = ex instanceof ProtocolException protocolException
                    && protocolException.errorCode()
                    == ProtocolErrorCode.GROUP_CREATE_RESULT_UNCONFIRMED;
            resultUnknown = resultUnknown || ex instanceof NormalGroupCreationLockLostException;
            resultUnknown = resultUnknown || (groupCreated && !groupJidPersisted);
            if (!resultUnknown && releaseForKafkaRetry(command, item, ex)) {
                throw ex;
            }
            fail(command, item, ex, resultUnknown, groupCreated);
        }
    }

    private void postProcess(NormalGroupCreationCommand command, ItemWork item) {
        if (mapper.claimStage(item.id(), "POST_PROCESSING", command.eventId(),
                "post", System.currentTimeMillis()) == 0) {
            return;
        }
        ProtocolAccountRef creator = account(item.creatorAccountId(),
                item.creatorProtocolBackend(), item.creatorProtocolAccountId(), item.creatorWsPhone());
        String[] leaveStatus = {null};
        boolean[] leaveAttempted = {false};
        try {
            accountLock.runWithLocks(
                    command.tenantId(), List.of(item.creatorAccountId()), () -> {
                        List<MemberWork> members = mapper.selectMemberWorks(item.id());
                        long now = System.currentTimeMillis();
                        Long groupLinkId = groupLinkRegistryService.registerSelfBuiltGroup(
                                item.groupJid(), item.groupSubject(), item.creatorAccountId(),
                                item.creatorWsPhone(), members.size() + 1, now);
                        if (mapper.updateGroupLink(item.id(), groupLinkId, now) != 1) {
                            throw new IllegalStateException("群组列表入口回写失败");
                        }
                        if (item.folderId() != null) {
                            groupLinkService.assignFolder(List.of(groupLinkId), item.folderId());
                        }

                        applySettings(creator, item);
                        GroupMetadataResult metadata =
                                metadataPort.getMetadata(creator, item.groupJid());
                        verifySettings(item, metadata);
                        for (MemberWork member : members) {
                            if (participantSucceeded(member.participantStatus())) {
                                groupLinkRegistryService.registerKnownMembership(
                                        groupLinkId, item.groupJid(), member.memberAccountId(),
                                        participantIsAdmin(metadata, member.memberWsPhone()), now);
                            }
                        }
                        leaveStatus[0] =
                                leaveCreatorIfRequired(
                                        creator, item, members, metadata,
                                        () -> leaveAttempted[0] = true);
                    });
            if (mapper.completePostProcess(
                    item.id(), "SUCCESS", leaveStatus[0], command.eventId(),
                    System.currentTimeMillis()) != 1) {
                throw new IllegalStateException("群后处理完成后状态推进失败");
            }
            migrateCreator(item.creatorAccountId(), item.successMigrationGroupId());
            mapper.refreshTaskSummary(item.taskId(), System.currentTimeMillis());
        } catch (RuntimeException ex) {
            boolean leaveResultUnknown = leaveAttempted[0] && leaveStatus[0] == null;
            if (!leaveResultUnknown && releaseForKafkaRetry(command, item, ex)) {
                throw ex;
            }
            fail(command, item, ex,
                    leaveResultUnknown || "SUCCESS".equals(leaveStatus[0]), true);
        }
    }

    private void applySettings(ProtocolAccountRef creator, ItemWork item) {
        groupSettingsPort.setSendMessagesAllowed(
                creator, item.groupJid(), Boolean.TRUE.equals(item.sendMessagesAllowed()));
        groupSettingsPort.setEditGroupSettingsAllowed(
                creator, item.groupJid(), Boolean.TRUE.equals(item.editGroupSettingsAllowed()));
        groupSettingsPort.setAddMembersAllowed(
                creator, item.groupJid(), Boolean.TRUE.equals(item.addMembersAllowed()));
        groupSettingsPort.setJoinApprovalEnabled(
                creator, item.groupJid(), Boolean.TRUE.equals(item.joinApprovalEnabled()));
        groupSettingsPort.setEphemeralDuration(
                creator, item.groupJid(), item.ephemeralDurationSeconds());
    }

    private void verifySettings(ItemWork item, GroupMetadataResult metadata) {
        if (metadata == null
                || !Objects.equals(metadata.announce(), !item.sendMessagesAllowed())
                || !Objects.equals(metadata.restrict(), !item.editGroupSettingsAllowed())
                || !Objects.equals(metadata.memberAddMode(), item.addMembersAllowed())
                || !Objects.equals(metadata.joinApprovalMode(), item.joinApprovalEnabled())
                || !Objects.equals(metadata.ephemeralDurationSeconds(),
                        item.ephemeralDurationSeconds())) {
            throw new IllegalStateException("群权限设置后的元数据回查不一致");
        }
    }

    private String leaveCreatorIfRequired(
            ProtocolAccountRef creator,
            ItemWork item,
            List<MemberWork> members,
            GroupMetadataResult metadata,
            Runnable beforeLeave) {
        if (!"LEAVE".equals(item.creatorLeavePolicy())) {
            return "SKIPPED";
        }
        GroupMetadataResult initialMetadata = metadata;
        MemberWork candidate = members.stream()
                .filter(member -> participantPresent(initialMetadata, member.memberWsPhone()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("群内没有可移交管理员的真实成员"));
        if (!participantIsAdmin(metadata, candidate.memberWsPhone())) {
            GroupParticipantBatchResult promoted = participantPort.updateParticipants(
                    creator,
                    item.groupJid(),
                    List.of(candidate.memberWsPhone()),
                    GroupParticipantAction.PROMOTE);
            if (promoted == null || promoted.partial()) {
                throw new IllegalStateException("提升新管理员未明确成功");
            }
            metadata = metadataPort.getMetadata(creator, item.groupJid());
        }
        boolean otherAdmin = metadata != null && metadata.participants() != null
                && metadata.participants().stream()
                .anyMatch(participant -> Boolean.TRUE.equals(participant.admin())
                        && !samePhone(participant, item.creatorWsPhone()));
        if (!otherAdmin) {
            throw new IllegalStateException("未确认群内存在其他真实管理员，保留建群人");
        }
        beforeLeave.run();
        groupLeavePort.leave(creator, item.groupJid());
        return "SUCCESS";
    }

    private void updateParticipantResults(List<MemberWork> members, GroupCreateResult result) {
        Map<String, GroupCreateParticipantResult> byPhone = new HashMap<>();
        if (result.results() != null) {
            for (GroupCreateParticipantResult row : result.results()) {
                byPhone.put(normalizePhone(row.jid()), row);
            }
        }
        for (MemberWork member : members) {
            GroupCreateParticipantResult row = byPhone.get(normalizePhone(member.memberWsPhone()));
            mapper.updateParticipantStatus(
                    member.id(), row == null ? "UNKNOWN" : normalizeStatus(row.status()),
                    row == null ? null : row.rawStatus(), System.currentTimeMillis());
        }
    }

    private void safePublish(
            String action, NormalGroupCreationCommand command, Long creatorAccountId) {
        try {
            publisher.publish(action, command.tenantId(), command.taskId(),
                    command.itemId(), creatorAccountId);
        } catch (RuntimeException ex) {
            log.warn("新建普群下一阶段消息暂未发布，将由低频补偿恢复 tenantId={} taskId={} "
                            + "itemId={} action={} exceptionType={}",
                    command.tenantId(), command.taskId(), command.itemId(), action,
                    ex.getClass().getSimpleName());
        }
    }

    private boolean releaseForKafkaRetry(
            NormalGroupCreationCommand command, ItemWork item, RuntimeException ex) {
        if (!isRetryable(ex)) {
            return false;
        }
        long now = System.currentTimeMillis();
        int updated = mapper.releaseStageForRetry(
                item.id(), expectedStep(command.action()), command.eventId(), maxStageAttempts,
                errorCode(ex),
                safeMessage(ex), now + RETRY_DISPATCH_DELAY_MS, now);
        if (updated == 1) {
            mapper.refreshTaskSummary(item.taskId(), now);
        }
        log.warn("新建普群临时故障交回 Kafka 重试 tenantId={} taskId={} itemId={} action={} "
                        + "backend={} stateReleased={} errorCode={} exceptionType={}",
                command.tenantId(), command.taskId(), command.itemId(), command.action(),
                item.creatorProtocolBackend(), updated == 1, errorCode(ex),
                ex.getClass().getSimpleName());
        return true;
    }

    private void fail(
            NormalGroupCreationCommand command,
            ItemWork item,
            RuntimeException ex,
            boolean resultUnknown,
            boolean groupCreated) {
        String errorCode = errorCode(ex);
        boolean groupAlreadyCreated = groupCreated
                || (item.groupJid() != null && !item.groupJid().isBlank());
        String terminalStatus = resultUnknown
                ? "RESULT_UNKNOWN" : groupAlreadyCreated ? "CREATED_PARTIAL" : "FAILED";
        int updated = mapper.failItem(
                item.id(), terminalStatus, errorCode,
                safeMessage(ex), command.eventId(), System.currentTimeMillis());
        if (updated != 1) {
            log.warn("新建普群失败状态未写入 tenantId={} taskId={} itemId={} action={} targetStatus={}",
                    command.tenantId(), command.taskId(), command.itemId(), command.action(),
                    terminalStatus);
            return;
        }
        migrateCreator(item.creatorAccountId(), groupAlreadyCreated
                ? item.successMigrationGroupId() : item.failedMigrationGroupId());
        mapper.refreshTaskSummary(item.taskId(), System.currentTimeMillis());
        log.warn("新建普群阶段失败 tenantId={} taskId={} itemId={} action={} backend={} "
                        + "errorCode={} exceptionType={}",
                command.tenantId(), command.taskId(), command.itemId(), command.action(),
                item.creatorProtocolBackend(), errorCode, ex.getClass().getSimpleName());
    }

    private void migrateCreator(Long creatorAccountId, Long targetGroupId) {
        if (targetGroupId == null) {
            return;
        }
        try {
            accountService.migrateGroup(List.of(creatorAccountId), targetGroupId);
        } catch (RuntimeException ex) {
            log.warn("新建普群建群账号迁移失败 accountId={} targetGroupId={} exceptionType={}",
                    creatorAccountId, targetGroupId, ex.getClass().getSimpleName());
        }
    }

    private static boolean isRetryable(RuntimeException ex) {
        if (ex instanceof NormalGroupCreationRetryableException) {
            return true;
        }
        if (!(ex instanceof ProtocolException protocolException)) {
            return false;
        }
        if (protocolException.errorCode()
                == ProtocolErrorCode.GROUP_CREATE_RESULT_UNCONFIRMED) {
            return false;
        }
        return protocolException.retryable().orElseGet(
                () -> DEFAULT_RETRYABLE_PROTOCOL_ERRORS.contains(protocolException.errorCode()));
    }

    private static String errorCode(RuntimeException ex) {
        return ex instanceof ProtocolException protocolException
                ? protocolException.errorCode().name() : ex.getClass().getSimpleName();
    }

    private static String expectedStep(String action) {
        return switch (action) {
            case "PREPARE" -> "PREPARING_CONTACTS";
            case "CREATE" -> "CREATING_GROUP";
            case "POST_PROCESS" -> "POST_PROCESSING";
            default -> throw new IllegalArgumentException("未知新建普群阶段: " + action);
        };
    }

    private static ProtocolAccountRef account(
            Long accountId, String backend, String protocolAccountId, String wsPhone) {
        return new ProtocolAccountRef(
                accountId,
                ProtocolBackend.valueOf(backend.toUpperCase(Locale.ROOT)),
                protocolAccountId,
                wsPhone);
    }

    private static boolean participantPresent(GroupMetadataResult metadata, String phone) {
        return metadata != null && metadata.participants() != null
                && metadata.participants().stream().anyMatch(row -> samePhone(row, phone));
    }

    private static boolean participantIsAdmin(GroupMetadataResult metadata, String phone) {
        return metadata != null && metadata.participants() != null
                && metadata.participants().stream()
                .anyMatch(row -> samePhone(row, phone)
                        && (Boolean.TRUE.equals(row.admin()) || Boolean.TRUE.equals(row.owner())));
    }

    private static boolean samePhone(GroupParticipantResult participant, String phone) {
        return normalizePhone(participant.phone() == null ? participant.jid() : participant.phone())
                .equals(normalizePhone(phone));
    }

    private static boolean participantSucceeded(String status) {
        return List.of("OK", "ALREADY_IN", "CONFIRMED").contains(normalizeStatus(status));
    }

    private static String normalizeStatus(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizePhone(String value) {
        if (value == null) {
            return "";
        }
        int at = value.indexOf('@');
        String raw = at >= 0 ? value.substring(0, at) : value;
        return raw.replaceAll("[^0-9]", "");
    }

    private static String operation(Long itemId, String step) {
        return "normal-group-" + itemId + "-" + step;
    }

    private static String safeMessage(Throwable ex) {
        if (ex instanceof ProtocolException protocol) {
            return switch (protocol.errorCode()) {
                case TIMEOUT -> "协议调用超时，请稍后重试";
                case NETWORK, HTTP_ERROR, TEMPORARY_FAILURE -> "协议服务暂时不可用，请稍后重试";
                case ACCOUNT_NOT_ONLINE -> "执行账号当前不在线";
                case ACCOUNT_BUSY, WORKER_BUSY, ONLINE_LIMITED, RECONNECT_LIMITED,
                        RATE_LIMITED -> "协议服务繁忙或触发限流，请稍后重试";
                case NEED_REAUTH -> "执行账号需要重新授权";
                case GROUP_PERMISSION_DENIED -> "执行账号没有目标群管理权限";
                case GROUP_CAPABILITY_UNSUPPORTED -> "当前协议不支持所需群操作";
                case GROUP_CREATE_RESULT_UNCONFIRMED -> "建群结果无法确认，必须人工对账";
                default -> "协议操作失败，请根据错误码处理";
            };
        }
        return "系统执行异常，请联系管理员";
    }

    private static void validateCommand(NormalGroupCreationCommand command) {
        if (command == null || command.schemaVersion() != 1 || command.eventId() == null
                || command.eventId().isBlank() || command.tenantId() == null
                || command.tenantId() <= 0 || command.taskId() == null || command.taskId() <= 0
                || command.itemId() == null || command.itemId() <= 0
                || command.action() == null || command.action().isBlank()) {
            throw new IllegalArgumentException("新建普群阶段消息字段非法");
        }
    }
}
