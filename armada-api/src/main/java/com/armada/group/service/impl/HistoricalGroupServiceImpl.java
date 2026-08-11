package com.armada.group.service.impl;

import com.armada.group.model.dto.HistoricalGroupParticipantActionDTO;
import com.armada.group.model.dto.GroupInviteLinkObservation;
import com.armada.group.model.enums.HistoricalGroupMembershipState;
import com.armada.group.model.enums.HistoricalGroupSelfRole;
import com.armada.group.model.enums.RoleCategory;
import com.armada.group.model.enums.SpeechState;
import com.armada.group.model.vo.HistoricalGroupDetailVO;
import com.armada.group.model.vo.HistoricalGroupParticipantActionVO;
import com.armada.group.service.HistoricalGroupExecutionAccountSelector;
import com.armada.group.service.GroupInviteLinkService;
import com.armada.group.service.HistoricalGroupProtocolPorts;
import com.armada.group.service.HistoricalGroupService;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账号组历史群详情与成员操作协议聚合实现。
 */
@Service
public class HistoricalGroupServiceImpl implements HistoricalGroupService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalGroupServiceImpl.class);
    private static final String NON_ADMIN_REASON = "当前账号不是管理员";
    private static final String INVITE_UNAVAILABLE_REASON = "群邀请链接不可用";
    private static final String PARTICIPANT_MUTATION_UNSUPPORTED_REASON =
            "当前协议暂不支持成员管理";
    private static final String PARTICIPANT_STATUS_OK = "OK";
    private static final String PARTICIPANT_RESULT_MISSING = "PROTOCOL_RESULT_MISSING";
    private static final String PARTICIPANT_MEMBER_NOT_FOUND = "MEMBER_NOT_FOUND";
    private static final String PARTICIPANT_SELF_PROTECTED = "SELF_PROTECTED";
    private static final String PARTICIPANT_OWNER_PROTECTED = "OWNER_PROTECTED";
    private static final String PARTICIPANT_ROLE_MISMATCH = "ROLE_MISMATCH";
    private static final String DETAIL_INVITE_SOURCE = "HISTORICAL_GROUP_DETAIL";
    private static final String PARTICIPANT_ACTION_INVITE_SOURCE =
            "HISTORICAL_GROUP_PARTICIPANT_ACTION";
    private static final int PARTICIPANT_BATCH_MAX_SIZE = 50;

    private final HistoricalGroupProtocolPorts protocolPorts;
    private final HistoricalGroupExecutionAccountSelector executionAccountSelector;
    private final GroupInviteLinkService inviteLinkService;

    /**
     * 创建账号组维度历史群服务。
     *
     * @param protocolPorts 历史群协议端口组合
     * @param executionAccountSelector 账号组历史群管理员选择器
     * @param inviteLinkService 当前群邀请链接事实服务
     */
    public HistoricalGroupServiceImpl(
            HistoricalGroupProtocolPorts protocolPorts,
            HistoricalGroupExecutionAccountSelector executionAccountSelector,
            GroupInviteLinkService inviteLinkService) {
        this.protocolPorts = protocolPorts;
        this.executionAccountSelector = executionAccountSelector;
        this.inviteLinkService = inviteLinkService;
    }

    /**
     * 按需读取账号组历史范围内单个群的完整实时详情。
     *
     * <p>账号组历史范围校验严格先于任何协议调用，执行账号由后台实时选择。</p>
     *
     * @param accountGroupId 来源账号组 ID
     * @param groupJid  历史群 JID
     * @return 包含完整成员和当前系统邀请链接的详情
     */
    @Override
    public HistoricalGroupDetailVO getHistoricalGroupDetail(Long accountGroupId, String groupJid) {
        OperationTarget target = operationTarget(accountGroupId, groupJid);
        ProtocolAccountRef account = target.account();
        String targetJid = target.groupJid();
        MetadataLookup metadataLookup = readDetailMetadata(account, targetJid);
        GroupMetadataResult metadata = metadataLookup.metadata();
        List<GroupParticipantResult> participants = metadata == null
                ? List.of()
                : metadata.participants();
        HistoricalGroupSelfRole accountRole = accountRole(account, participants);
        boolean accountAdmin = accountRole == HistoricalGroupSelfRole.ADMIN
                || accountRole == HistoricalGroupSelfRole.OWNER;
        InviteLookup inviteLookup = metadata != null && accountAdmin
                ? readDetailInvite(account, targetJid)
                : new InviteLookup(null, null, null);
        String errorCode = joinErrors(metadataLookup.errorCode(), inviteLookup.errorCode());
        String errorMessage = joinErrors(metadataLookup.errorMessage(), inviteLookup.errorMessage());
        boolean participantMutationSupported = metadata != null
                && metadata.participantMutationSupported();
        boolean operationAllowed = metadata != null
                && participantMutationSupported
                && accountAdmin;
        String disabledReason = metadata == null
                ? metadataLookup.errorMessage()
                : !participantMutationSupported
                        ? PARTICIPANT_MUTATION_UNSUPPORTED_REASON
                        : accountAdmin ? null : NON_ADMIN_REASON;
        List<HistoricalGroupDetailVO.Member> members = detailMembers(
                account, participants, operationAllowed, disabledReason);
        return new HistoricalGroupDetailVO(
                account.armadaAccountId(),
                targetJid,
                metadata == null ? null : blankToNull(metadata.subject()),
                metadata == null
                        ? HistoricalGroupMembershipState.FETCH_FAILED
                        : HistoricalGroupMembershipState.CURRENT_IN_GROUP,
                roleCategory(accountRole),
                accountRole,
                metadata == null || metadata.stateAbnormal()
                        ? SpeechState.ABNORMAL
                        : detailSpeechState(metadata.announce(), accountRole),
                metadata == null ? null : members.size(),
                metadata == null ? null : metadata.announce(),
                inviteLookup.inviteUrl(),
                inviteLookup.inviteUrl() != null,
                operationAllowed,
                disabledReason,
                errorCode,
                errorMessage,
                members);
    }

    private MetadataLookup readDetailMetadata(ProtocolAccountRef account, String groupJid) {
        try {
            return new MetadataLookup(
                    protocolPorts.readMetadata().getMetadata(account, groupJid),
                    null,
                    null);
        } catch (ProtocolException ex) {
            log.warn("历史群详情 metadata 读取失败 accountId={} reasonCode={} httpStatus={}",
                    account.armadaAccountId(), ex.errorCode(), ex.httpStatus());
            return new MetadataLookup(null, ex.errorCode().name(), ex.getMessage());
        }
    }

    private InviteLookup readDetailInvite(ProtocolAccountRef account, String groupJid) {
        try {
            GroupInviteResult invite = protocolPorts.invite().getInvite(account, groupJid);
            observeCurrentInvite(account, groupJid, invite, DETAIL_INVITE_SOURCE);
            String inviteUrl = invite == null ? null : blankToNull(invite.inviteUrl());
            return inviteUrl == null
                    ? new InviteLookup(null, ProtocolErrorCode.INVALID_GROUP_LINK.name(), INVITE_UNAVAILABLE_REASON)
                    : new InviteLookup(inviteUrl, null, null);
        } catch (ProtocolException ex) {
            log.warn("历史群详情邀请链接读取失败 accountId={} reasonCode={} httpStatus={}",
                    account.armadaAccountId(), ex.errorCode(), ex.httpStatus());
            return new InviteLookup(null, ex.errorCode().name(), ex.getMessage());
        }
    }

    /**
     * 使用后台自动选择的群主或管理员批量提升历史群普通成员。
     *
     * <p>协议写入前重新读取实时 metadata，校验当前账号管理员身份和目标成员状态；提升操作不依赖邀请链接。</p>
     *
     * @param dto 账号组、历史群和目标成员
     * @return 按请求顺序返回的协议逐项结果
     */
    @Override
    public HistoricalGroupParticipantActionVO promoteParticipants(
            HistoricalGroupParticipantActionDTO dto) {
        return updateParticipants(dto, GroupParticipantAction.PROMOTE);
    }

    /**
     * 使用后台自动选择的群主或管理员批量降级历史群内其他管理员。
     *
     * @param dto 账号组、历史群和目标成员
     * @return 按请求顺序返回的本地保护与协议逐项结果
     */
    @Override
    public HistoricalGroupParticipantActionVO demoteParticipants(
            HistoricalGroupParticipantActionDTO dto) {
        return updateParticipants(dto, GroupParticipantAction.DEMOTE);
    }

    /**
     * 使用后台自动选择的群主或管理员批量移除历史群内可操作成员。
     *
     * @param dto 账号组、历史群和目标成员
     * @return 按请求顺序返回的本地保护与协议逐项结果
     */
    @Override
    public HistoricalGroupParticipantActionVO removeParticipants(
            HistoricalGroupParticipantActionDTO dto) {
        return updateParticipants(dto, GroupParticipantAction.REMOVE);
    }

    private HistoricalGroupParticipantActionVO updateParticipants(
            HistoricalGroupParticipantActionDTO dto,
            GroupParticipantAction action) {
        List<String> requestedJids = requireParticipantJids(dto);
        OperationTarget target = operationTarget(dto.accountGroupId(), dto.groupJid());
        ProtocolAccountRef account = target.account();
        String groupJid = target.groupJid();
        GroupMetadataResult metadata = requireActionMetadata(account, groupJid, action);
        requireAdministrator(account, metadata.participants());
        if (action != GroupParticipantAction.PROMOTE) {
            requireFreshInvite(account, groupJid);
        }
        Map<String, GroupParticipantResult> currentMembers = participantsByJid(metadata.participants());
        Map<String, HistoricalGroupParticipantActionVO.Result> results = new LinkedHashMap<>();
        List<String> actionable = new ArrayList<>();
        for (String participantJid : requestedJids) {
            GroupParticipantResult participant = currentMembers.get(participantJid);
            HistoricalGroupParticipantActionVO.Result rejected = rejectedParticipant(
                    account, participantJid, participant, action);
            if (rejected == null) {
                actionable.add(participantJid);
            } else {
                results.put(participantJid, rejected);
            }
        }
        results.putAll(callParticipantProtocol(account, groupJid, actionable, action));
        HistoricalGroupParticipantActionVO response = orderedActionResult(requestedJids, results);
        long successCount = response.results().stream()
                .filter(HistoricalGroupParticipantActionVO.Result::success)
                .count();
        log.info("历史群成员操作完成 accountGroupId={} action={} requestedCount={} protocolTargetCount={} "
                        + "successCount={} partial={}",
                dto.accountGroupId(), action, requestedJids.size(), actionable.size(), successCount, response.partial());
        return response;
    }

    private OperationTarget operationTarget(Long accountGroupId, String groupJid) {
        String targetJid = blankToNull(groupJid);
        com.armada.group.model.vo.GroupExecutionAccount selected =
                executionAccountSelector.require(accountGroupId, targetJid);
        return new OperationTarget(selected.protocolRef(), targetJid);
    }

    private GroupMetadataResult requireActionMetadata(
            ProtocolAccountRef account,
            String groupJid,
            GroupParticipantAction action) {
        try {
            if (action == GroupParticipantAction.PROMOTE) {
                return protocolPorts.readMetadata().getMetadata(account, groupJid);
            }
            return protocolPorts.writeMetadata().getMetadata(account.protocolAccountId(), groupJid);
        } catch (ProtocolException ex) {
            log.warn("历史群成员操作写前 metadata 读取失败 accountId={} action={} reasonCode={} httpStatus={}",
                    account.armadaAccountId(), action, ex.errorCode(), ex.httpStatus());
            throw new BusinessException(ErrorCode.VALIDATION, ex.getMessage());
        }
    }

    private static RoleCategory roleCategory(HistoricalGroupSelfRole selfRole) {
        if (selfRole == null) {
            return null;
        }
        return switch (selfRole) {
            case OWNER, ADMIN -> RoleCategory.ADMIN;
            case MEMBER -> RoleCategory.MEMBER;
        };
    }

    private static SpeechState detailSpeechState(
            Boolean announceOnly,
            HistoricalGroupSelfRole selfRole) {
        if (Boolean.FALSE.equals(announceOnly)) {
            return SpeechState.NORMAL;
        }
        if (!Boolean.TRUE.equals(announceOnly) || selfRole == null) {
            return SpeechState.ABNORMAL;
        }
        return selfRole == HistoricalGroupSelfRole.MEMBER
                ? SpeechState.CANNOT_SPEAK
                : SpeechState.ADMIN_CAN_SPEAK;
    }

    private static HistoricalGroupSelfRole accountRole(
            ProtocolAccountRef account,
            List<GroupParticipantResult> participants) {
        if (participants == null) {
            return null;
        }
        String accountPhone = phoneKey(account.wsPhone());
        for (GroupParticipantResult participant : participants) {
            if (participant != null && accountPhone.equals(phoneKey(
                    firstText(participant.phone(), participant.jid())))) {
                return participantRole(participant);
            }
        }
        return null;
    }

    private static List<HistoricalGroupDetailVO.Member> detailMembers(
            ProtocolAccountRef account,
            List<GroupParticipantResult> participants,
            boolean accountCanOperate,
            String globalDisabledReason) {
        if (participants == null || participants.isEmpty()) {
            return List.of();
        }
        String accountPhone = phoneKey(account.wsPhone());
        List<HistoricalGroupDetailVO.Member> members = new ArrayList<>(participants.size());
        for (GroupParticipantResult participant : participants) {
            if (participant == null || blankToNull(participant.jid()) == null) {
                continue;
            }
            String phone = firstText(participant.phone(), phoneKey(participant.jid()));
            boolean self = accountPhone.equals(phoneKey(phone));
            boolean owner = Boolean.TRUE.equals(participant.owner());
            boolean administrator = owner || Boolean.TRUE.equals(participant.admin());
            boolean allowed = accountCanOperate && !self && !administrator;
            String disabledReason = allowed ? null
                    : owner ? "群主已经是管理员"
                    : self ? "操作账号本人不能作为提升目标"
                    : administrator ? "目标成员已经是管理员"
                    : globalDisabledReason;
            members.add(new HistoricalGroupDetailVO.Member(
                    participant.jid().trim(),
                    phone,
                    self,
                    owner,
                    administrator,
                    participantRole(participant),
                    allowed,
                    disabledReason));
        }
        return List.copyOf(members);
    }

    private static HistoricalGroupSelfRole participantRole(GroupParticipantResult participant) {
        if (Boolean.TRUE.equals(participant.owner())) {
            return HistoricalGroupSelfRole.OWNER;
        }
        if (Boolean.TRUE.equals(participant.admin())) {
            return HistoricalGroupSelfRole.ADMIN;
        }
        return HistoricalGroupSelfRole.MEMBER;
    }

    private void requireAdministrator(
            ProtocolAccountRef account,
            List<GroupParticipantResult> participants) {
        HistoricalGroupSelfRole selfRole = accountRole(account, participants);
        if (selfRole != HistoricalGroupSelfRole.ADMIN
                && selfRole != HistoricalGroupSelfRole.OWNER) {
            throw new BusinessException(ErrorCode.GROUP_PERMISSION_DENIED, NON_ADMIN_REASON);
        }
    }

    private void requireFreshInvite(ProtocolAccountRef account, String groupJid) {
        GroupInviteResult invite;
        try {
            invite = protocolPorts.invite().getInvite(account, groupJid);
        } catch (ProtocolException ex) {
            log.warn("历史群成员操作写前邀请链接读取失败 accountId={} reasonCode={} httpStatus={}",
                    account.armadaAccountId(), ex.errorCode(), ex.httpStatus());
            throw new BusinessException(ErrorCode.VALIDATION, ex.getMessage());
        }
        if (invite == null || blankToNull(invite.inviteUrl()) == null) {
            throw new BusinessException(ErrorCode.VALIDATION, INVITE_UNAVAILABLE_REASON);
        }
        observeCurrentInvite(account, groupJid, invite, PARTICIPANT_ACTION_INVITE_SOURCE);
    }

    private void observeCurrentInvite(
            ProtocolAccountRef account,
            String groupJid,
            GroupInviteResult invite,
            String source) {
        String inviteCode = inviteCode(invite);
        if (inviteCode == null) {
            return;
        }
        long observedAt = System.currentTimeMillis();
        inviteLinkService.applyCurrentInvite(new GroupInviteLinkObservation(
                "historical-group:" + account.armadaAccountId() + ":" + observedAt,
                null, groupJid, inviteCode, account.backend(), source, observedAt));
    }

    private static String inviteCode(GroupInviteResult invite) {
        if (invite == null) {
            return null;
        }
        String code = blankToNull(invite.inviteCode());
        if (code != null) {
            return code;
        }
        String url = blankToNull(invite.inviteUrl());
        if (url == null) {
            return null;
        }
        int slash = url.lastIndexOf('/');
        return slash < 0 || slash == url.length() - 1
                ? null
                : blankToNull(url.substring(slash + 1));
    }

    private static List<String> requireParticipantJids(HistoricalGroupParticipantActionDTO dto) {
        if (dto == null || dto.participantJids() == null
                || dto.participantJids().isEmpty()
                || dto.participantJids().size() > PARTICIPANT_BATCH_MAX_SIZE) {
            throw new BusinessException(
                    ErrorCode.VALIDATION,
                    "群成员操作每次必须选择 1 到 " + PARTICIPANT_BATCH_MAX_SIZE + " 人");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String participantJid : dto.participantJids()) {
            String jid = blankToNull(participantJid);
            if (jid == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "群成员 JID 不能为空");
            }
            normalized.add(jid);
        }
        return List.copyOf(normalized);
    }

    private static Map<String, GroupParticipantResult> participantsByJid(
            List<GroupParticipantResult> participants) {
        Map<String, GroupParticipantResult> members = new LinkedHashMap<>();
        if (participants == null) {
            return members;
        }
        for (GroupParticipantResult participant : participants) {
            if (participant != null && blankToNull(participant.jid()) != null) {
                members.putIfAbsent(participant.jid().trim(), participant);
            }
        }
        return members;
    }

    private static HistoricalGroupParticipantActionVO.Result rejectedParticipant(
            ProtocolAccountRef account,
            String participantJid,
            GroupParticipantResult participant,
            GroupParticipantAction action) {
        if (participant == null) {
            return failedParticipant(
                    participantJid, PARTICIPANT_MEMBER_NOT_FOUND, "目标成员已不在群内");
        }
        boolean self = phoneKey(account.wsPhone()).equals(phoneKey(
                firstText(participant.phone(), participant.jid())));
        boolean owner = Boolean.TRUE.equals(participant.owner());
        boolean admin = owner || Boolean.TRUE.equals(participant.admin());
        return switch (action) {
            case PROMOTE -> admin
                    ? failedParticipant(participantJid, PARTICIPANT_ROLE_MISMATCH, "目标成员不是普通成员")
                    : null;
            case DEMOTE -> self
                    ? failedParticipant(participantJid, PARTICIPANT_SELF_PROTECTED, "操作账号本人不能被降级")
                    : owner
                    ? failedParticipant(participantJid, PARTICIPANT_OWNER_PROTECTED, "群主不能被降级")
                    : !admin
                    ? failedParticipant(participantJid, PARTICIPANT_ROLE_MISMATCH, "目标成员不是管理员")
                    : null;
            case REMOVE -> self
                    ? failedParticipant(participantJid, PARTICIPANT_SELF_PROTECTED, "操作账号本人不能被踢出")
                    : owner
                    ? failedParticipant(participantJid, PARTICIPANT_OWNER_PROTECTED, "群主不能被踢出")
                    : null;
            case ADD -> failedParticipant(participantJid, PARTICIPANT_ROLE_MISMATCH, "历史群成员管理不支持添加动作");
        };
    }

    private Map<String, HistoricalGroupParticipantActionVO.Result> callParticipantProtocol(
            ProtocolAccountRef account,
            String groupJid,
            List<String> actionable,
            GroupParticipantAction action) {
        if (actionable.isEmpty()) {
            return Map.of();
        }
        GroupParticipantBatchResult protocolResult;
        try {
            protocolResult = protocolPorts.participants().updateParticipants(
                    account, groupJid, actionable, action);
        } catch (ProtocolException ex) {
            log.warn("历史群成员协议操作失败 accountId={} action={} targetCount={} reasonCode={} httpStatus={}",
                    account.armadaAccountId(), action, actionable.size(), ex.errorCode(), ex.httpStatus());
            Map<String, HistoricalGroupParticipantActionVO.Result> failures = new LinkedHashMap<>();
            for (String participantJid : actionable) {
                failures.put(participantJid, failedParticipant(
                        participantJid, ex.errorCode().name(), ex.getMessage()));
            }
            return failures;
        }
        return protocolResultsByJid(actionable, protocolResult);
    }

    private static Map<String, HistoricalGroupParticipantActionVO.Result> protocolResultsByJid(
            List<String> actionable,
            GroupParticipantBatchResult protocolResult) {
        Map<String, GroupParticipantBatchResult.Item> protocolItems = new LinkedHashMap<>();
        if (protocolResult != null && protocolResult.results() != null) {
            for (GroupParticipantBatchResult.Item item : protocolResult.results()) {
                if (item != null && blankToNull(item.jid()) != null) {
                    protocolItems.putIfAbsent(item.jid().trim(), item);
                }
            }
        }
        Map<String, HistoricalGroupParticipantActionVO.Result> results = new LinkedHashMap<>();
        for (String participantJid : actionable) {
            GroupParticipantBatchResult.Item item = protocolItems.get(participantJid);
            if (item == null) {
                results.put(participantJid, failedParticipant(
                        participantJid, PARTICIPANT_RESULT_MISSING, "协议未返回该成员结果"));
                continue;
            }
            String status = blankToNull(item.status());
            boolean success = PARTICIPANT_STATUS_OK.equals(status);
            String errorCode = success ? null
                    : status == null ? PARTICIPANT_RESULT_MISSING : status;
            String errorMessage = success ? null
                    : firstText(item.rawStatus(), status == null ? "协议成员结果缺少状态" : status);
            results.put(participantJid, new HistoricalGroupParticipantActionVO.Result(
                    participantJid,
                    success,
                    status,
                    errorCode,
                    errorMessage));
        }
        return results;
    }

    private static HistoricalGroupParticipantActionVO orderedActionResult(
            List<String> requestedJids,
            Map<String, HistoricalGroupParticipantActionVO.Result> byJid) {
        List<HistoricalGroupParticipantActionVO.Result> results = requestedJids.stream()
                .map(byJid::get)
                .toList();
        long successCount = results.stream()
                .filter(HistoricalGroupParticipantActionVO.Result::success)
                .count();
        boolean ok = successCount == results.size();
        boolean partial = successCount > 0 && successCount < results.size();
        return new HistoricalGroupParticipantActionVO(ok, partial, results);
    }

    private static HistoricalGroupParticipantActionVO.Result failedParticipant(
            String participantJid,
            String errorCode,
            String errorMessage) {
        return new HistoricalGroupParticipantActionVO.Result(
                participantJid, false, null, errorCode, errorMessage);
    }

    private static String phoneKey(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return "";
        }
        int at = normalized.indexOf('@');
        if (at >= 0) {
            normalized = normalized.substring(0, at);
        }
        int device = normalized.indexOf(':');
        if (device >= 0) {
            normalized = normalized.substring(0, device);
        }
        return normalized.startsWith("+") ? normalized.substring(1) : normalized;
    }

    private static String firstText(String preferred, String fallback) {
        String value = blankToNull(preferred);
        return value == null ? blankToNull(fallback) : value;
    }

    private static String joinErrors(String first, String second) {
        String firstValue = blankToNull(first);
        String secondValue = blankToNull(second);
        if (firstValue == null) {
            return secondValue;
        }
        if (secondValue == null) {
            return firstValue;
        }
        return firstValue + "；" + secondValue;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record MetadataLookup(
            GroupMetadataResult metadata,
            String errorCode,
            String errorMessage) {
    }

    private record InviteLookup(
            String inviteUrl,
            String errorCode,
            String errorMessage) {
    }

    private record OperationTarget(
            ProtocolAccountRef account,
            String groupJid) {
    }
}
