package com.armada.group.service.impl;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.model.dto.HistoricalGroupParticipantActionDTO;
import com.armada.group.model.enums.HistoricalGroupMembershipState;
import com.armada.group.model.enums.HistoricalGroupSelfRole;
import com.armada.group.model.enums.RoleCategory;
import com.armada.group.model.enums.SpeechState;
import com.armada.group.model.vo.AccountGroupBaselineRow;
import com.armada.group.model.vo.HistoricalGroupDetailVO;
import com.armada.group.model.vo.HistoricalGroupItemVO;
import com.armada.group.model.vo.HistoricalGroupParticipantActionVO;
import com.armada.group.service.HistoricalGroupProtocolPorts;
import com.armada.group.service.HistoricalGroupService;
import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.result.AccountGroupMetadataSummaryResult;
import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import com.armada.platform.protocol.model.result.GroupInviteResult;
import com.armada.platform.protocol.model.result.GroupMetadataResult;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 历史群列表与请求级协议刷新聚合实现。
 *
 * <p>baseline JID 是唯一展示范围,本服务不持久化当前状态,也不反写 baseline 群名。</p>
 */
@Service
public class HistoricalGroupServiceImpl implements HistoricalGroupService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalGroupServiceImpl.class);
    private static final int SUMMARY_CONCURRENCY = 8;
    private static final String MISSING_SUMMARY_ERROR = "协议摘要缺少该群结果";
    private static final String UNKNOWN_COUNT = "unknown";
    private static final String NON_ADMIN_REASON = "当前账号不是管理员";
    private static final String INVITE_UNAVAILABLE_REASON = "群邀请链接不可用";
    private static final String PARTICIPANT_STATUS_OK = "OK";
    private static final String PARTICIPANT_RESULT_MISSING = "PROTOCOL_RESULT_MISSING";
    private static final String PARTICIPANT_MEMBER_NOT_FOUND = "MEMBER_NOT_FOUND";
    private static final String PARTICIPANT_SELF_PROTECTED = "SELF_PROTECTED";
    private static final String PARTICIPANT_OWNER_PROTECTED = "OWNER_PROTECTED";
    private static final String PARTICIPANT_ROLE_MISMATCH = "ROLE_MISMATCH";
    private static final int PARTICIPANT_BATCH_MAX_SIZE = 50;

    private final AccountProtocolLookupService accountLookupService;
    private final AccountGroupMembershipMapper membershipMapper;
    private final HistoricalGroupProtocolPorts protocolPorts;
    private final ObjectMapper objectMapper;

    /**
     * 创建历史群聚合服务。
     *
     * @param accountLookupService   当前租户账号协议引用查询服务
     * @param membershipMapper       baseline 数据访问
     * @param protocolPorts          历史群查询与成员操作协议端口组合
     * @param objectMapper           baseline JSON 解析器
     */
    public HistoricalGroupServiceImpl(AccountProtocolLookupService accountLookupService,
                                      AccountGroupMembershipMapper membershipMapper,
                                      HistoricalGroupProtocolPorts protocolPorts,
                                      ObjectMapper objectMapper) {
        this.accountLookupService = accountLookupService;
        this.membershipMapper = membershipMapper;
        this.protocolPorts = protocolPorts;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取 baseline 历史群并保持 JID 存储顺序。
     *
     * <p>先经账号域 Service 校验当前租户可见性和协议身份,避免群组域绕过租户边界直接查账号表。</p>
     *
     * @param accountId 操作账号 ID
     * @return 按 baseline JID 顺序排列的未验证历史群
     */
    @Override
    public List<HistoricalGroupItemVO> listHistoricalGroups(Long accountId) {
        requireAccount(accountId);
        BaselineSnapshot baseline = loadBaseline(accountId);
        List<HistoricalGroupItemVO> items = new ArrayList<>(baseline.groupJids().size());
        for (String groupJid : baseline.groupJids()) {
            items.add(new HistoricalGroupItemVO(
                    groupJid,
                    baseline.subjects().get(groupJid),
                    HistoricalGroupMembershipState.UNVERIFIED,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null));
        }
        return List.copyOf(items);
    }

    /**
     * 请求级刷新历史群当前状态。
     *
     * <p>先以轻量当前群列表判定在群/已退出,再只对 baseline 交集请求摘要。
     * 协议返回的非 baseline 群永不进入响应,避免生成第二套历史范围。</p>
     *
     * @param accountId 操作账号 ID
     * @return 按 baseline JID 顺序排列的请求级刷新结果
     */
    @Override
    public List<HistoricalGroupItemVO> refreshHistoricalGroups(Long accountId) {
        ProtocolAccountRef account = requireAccount(accountId);
        BaselineSnapshot baseline = loadBaseline(accountId);
        if (baseline.groupJids().isEmpty()) {
            log.info("历史群刷新完成 accountId={} baselineGroups=0 currentGroups=0 intersectionGroups=0", accountId);
            return List.of();
        }

        Map<String, AccountParticipatingGroupResult.Group> currentGroups;
        try {
            currentGroups = currentGroupsByJid(protocolPorts.participatingGroups().listCurrent(account));
        } catch (ProtocolException ex) {
            // 当前群整体失败时不能把未知误判为已退出;错误完整回给请求,日志只记录安全诊断字段。
            log.warn("历史群轻量列表获取失败 accountId={} baselineGroups={} currentGroups={} "
                            + "intersectionGroups={} reasonCode={} httpStatus={}",
                    accountId, baseline.groupJids().size(), UNKNOWN_COUNT, UNKNOWN_COUNT,
                    ex.errorCode(), ex.httpStatus());
            return fetchFailedItems(baseline, ex.getMessage());
        }

        List<String> intersection = baseline.groupJids().stream()
                .filter(currentGroups::containsKey)
                .toList();
        List<HistoricalGroupItemVO> items = membershipItems(baseline, currentGroups);
        if (!intersection.isEmpty()) {
            items = applySummary(account, accountId, items, intersection, currentGroups.size());
        }
        log.info("历史群刷新完成 accountId={} baselineGroups={} currentGroups={} intersectionGroups={}",
                accountId, baseline.groupJids().size(), currentGroups.size(), intersection.size());
        return List.copyOf(items);
    }

    /**
     * 按需读取 baseline 内单个历史群的完整实时详情。
     *
     * <p>baseline 范围校验严格先于任何协议调用，且始终使用页面固定的操作账号。</p>
     *
     * @param accountId 固定操作账号 ID
     * @param groupJid  baseline 群 JID
     * @return 包含完整成员和当前系统邀请链接的详情
     */
    @Override
    public HistoricalGroupDetailVO getHistoricalGroupDetail(Long accountId, String groupJid) {
        ProtocolAccountRef account = requireAccount(accountId);
        BaselineSnapshot baseline = loadBaseline(accountId);
        String targetJid = requireBaselineGroup(baseline, groupJid);
        MetadataLookup metadataLookup = readDetailMetadata(account, targetJid);
        InviteLookup inviteLookup = readDetailInvite(account, targetJid);
        GroupMetadataResult metadata = metadataLookup.metadata();
        List<GroupParticipantResult> participants = metadata == null
                ? List.of()
                : metadata.participants();
        HistoricalGroupSelfRole accountRole = accountRole(account, participants);
        boolean accountAdmin = accountRole == HistoricalGroupSelfRole.ADMIN
                || accountRole == HistoricalGroupSelfRole.OWNER;
        String errorCode = joinErrors(metadataLookup.errorCode(), inviteLookup.errorCode());
        String errorMessage = joinErrors(metadataLookup.errorMessage(), inviteLookup.errorMessage());
        boolean operationAllowed = metadata != null && accountAdmin && inviteLookup.inviteUrl() != null;
        String disabledReason = errorMessage != null ? errorMessage
                : accountAdmin ? null : NON_ADMIN_REASON;
        List<HistoricalGroupDetailVO.Member> members = detailMembers(
                account, participants, operationAllowed, disabledReason);
        return new HistoricalGroupDetailVO(
                accountId,
                targetJid,
                firstText(metadata == null ? null : metadata.subject(), baseline.subjects().get(targetJid)),
                metadata == null
                        ? HistoricalGroupMembershipState.FETCH_FAILED
                        : HistoricalGroupMembershipState.CURRENT_IN_GROUP,
                roleCategory(accountRole),
                accountRole,
                metadata == null ? SpeechState.ABNORMAL : detailSpeechState(metadata.announce(), accountRole),
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
                    protocolPorts.metadata().getMetadata(account.protocolAccountId(), groupJid),
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
     * 使用固定管理员账号批量提升 baseline 群普通成员。
     *
     * <p>协议写入前重新读取实时 metadata 和非空邀请链接，不接受浏览器回传链接。</p>
     *
     * @param dto 固定操作账号、baseline 群和目标成员
     * @return 按请求顺序返回的协议逐项结果
     */
    @Override
    public HistoricalGroupParticipantActionVO promoteParticipants(
            HistoricalGroupParticipantActionDTO dto) {
        return updateParticipants(dto, GroupParticipantAction.PROMOTE);
    }

    /**
     * 使用固定管理员账号批量降级 baseline 群内其他管理员。
     *
     * @param dto 固定操作账号、baseline 群和目标成员
     * @return 按请求顺序返回的本地保护与协议逐项结果
     */
    @Override
    public HistoricalGroupParticipantActionVO demoteParticipants(
            HistoricalGroupParticipantActionDTO dto) {
        return updateParticipants(dto, GroupParticipantAction.DEMOTE);
    }

    /**
     * 使用固定管理员账号批量移除 baseline 群内可操作成员。
     *
     * @param dto 固定操作账号、baseline 群和目标成员
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
        ProtocolAccountRef account = requireAccount(dto.accountId());
        BaselineSnapshot baseline = loadBaseline(dto.accountId());
        String groupJid = requireBaselineGroup(baseline, dto.groupJid());
        GroupMetadataResult metadata = requireActionMetadata(account, groupJid, action);
        requireAdministrator(account, metadata.participants());
        requireFreshInvite(account, groupJid);
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
        log.info("历史群成员操作完成 accountId={} action={} requestedCount={} protocolTargetCount={} "
                        + "successCount={} partial={}",
                dto.accountId(), action, requestedJids.size(), actionable.size(), successCount, response.partial());
        return response;
    }

    private GroupMetadataResult requireActionMetadata(
            ProtocolAccountRef account,
            String groupJid,
            GroupParticipantAction action) {
        try {
            return protocolPorts.metadata().getMetadata(account.protocolAccountId(), groupJid);
        } catch (ProtocolException ex) {
            log.warn("历史群成员操作写前 metadata 读取失败 accountId={} action={} reasonCode={} httpStatus={}",
                    account.armadaAccountId(), action, ex.errorCode(), ex.httpStatus());
            throw new BusinessException(ErrorCode.VALIDATION, ex.getMessage());
        }
    }

    private ProtocolAccountRef requireAccount(Long accountId) {
        if (accountId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "操作账号 ID 不能为空");
        }
        return accountLookupService.findActiveProtocolRef(accountId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "操作账号不存在或协议身份不完整: " + accountId));
    }

    private List<HistoricalGroupItemVO> applySummary(
            ProtocolAccountRef account,
            Long accountId,
            List<HistoricalGroupItemVO> items,
            List<String> intersection,
            int currentGroupCount) {
        List<AccountGroupMetadataSummaryResult> summaries;
        try {
            summaries = protocolPorts.participatingGroups().summarize(account, intersection, SUMMARY_CONCURRENCY);
        } catch (ProtocolException ex) {
            // 摘要整体失败不影响轻量列表已经确认的在群/已退出结论。
            log.warn("历史群摘要整体获取失败 accountId={} baselineGroups={} currentGroups={} "
                            + "intersectionGroups={} reasonCode={} httpStatus={}",
                    accountId, items.size(), currentGroupCount, intersection.size(), ex.errorCode(), ex.httpStatus());
            return markCurrentItemsAbnormal(items, ex.getMessage());
        }
        Map<String, AccountGroupMetadataSummaryResult> byJid = summariesByJid(summaries);
        List<HistoricalGroupItemVO> enriched = new ArrayList<>(items.size());
        for (HistoricalGroupItemVO item : items) {
            if (item.membershipState() != HistoricalGroupMembershipState.CURRENT_IN_GROUP) {
                enriched.add(item);
                continue;
            }
            enriched.add(summaryItem(item, byJid.get(item.groupJid())));
        }
        return enriched;
    }

    private static Map<String, AccountParticipatingGroupResult.Group> currentGroupsByJid(
            List<AccountParticipatingGroupResult.Group> groups) {
        Map<String, AccountParticipatingGroupResult.Group> current = new LinkedHashMap<>();
        if (groups == null) {
            return current;
        }
        for (AccountParticipatingGroupResult.Group group : groups) {
            if (group == null || blankToNull(group.groupJid()) == null) {
                continue;
            }
            current.putIfAbsent(group.groupJid().trim(), group);
        }
        return current;
    }

    private static List<HistoricalGroupItemVO> membershipItems(
            BaselineSnapshot baseline,
            Map<String, AccountParticipatingGroupResult.Group> currentGroups) {
        List<HistoricalGroupItemVO> items = new ArrayList<>(baseline.groupJids().size());
        for (String groupJid : baseline.groupJids()) {
            AccountParticipatingGroupResult.Group current = currentGroups.get(groupJid);
            HistoricalGroupMembershipState state = current == null
                    ? HistoricalGroupMembershipState.CURRENT_NOT_IN_GROUP
                    : HistoricalGroupMembershipState.CURRENT_IN_GROUP;
            String subject = current == null
                    ? baseline.subjects().get(groupJid)
                    : firstText(current.subject(), baseline.subjects().get(groupJid));
            items.add(emptyItem(groupJid, subject, state, null, null));
        }
        return items;
    }

    private static List<HistoricalGroupItemVO> fetchFailedItems(BaselineSnapshot baseline, String error) {
        List<HistoricalGroupItemVO> items = new ArrayList<>(baseline.groupJids().size());
        for (String groupJid : baseline.groupJids()) {
            items.add(emptyItem(
                    groupJid,
                    baseline.subjects().get(groupJid),
                    HistoricalGroupMembershipState.FETCH_FAILED,
                    SpeechState.ABNORMAL,
                    error));
        }
        return List.copyOf(items);
    }

    private static List<HistoricalGroupItemVO> markCurrentItemsAbnormal(
            List<HistoricalGroupItemVO> items,
            String error) {
        List<HistoricalGroupItemVO> failed = new ArrayList<>(items.size());
        for (HistoricalGroupItemVO item : items) {
            if (item.membershipState() == HistoricalGroupMembershipState.CURRENT_IN_GROUP) {
                failed.add(emptyItem(
                        item.groupJid(), item.subject(), item.membershipState(), SpeechState.ABNORMAL, error));
            } else {
                failed.add(item);
            }
        }
        return failed;
    }

    private static Map<String, AccountGroupMetadataSummaryResult> summariesByJid(
            List<AccountGroupMetadataSummaryResult> summaries) {
        Map<String, AccountGroupMetadataSummaryResult> byJid = new LinkedHashMap<>();
        if (summaries == null) {
            return byJid;
        }
        for (AccountGroupMetadataSummaryResult summary : summaries) {
            if (summary != null && blankToNull(summary.groupJid()) != null) {
                byJid.putIfAbsent(summary.groupJid().trim(), summary);
            }
        }
        return byJid;
    }

    private static HistoricalGroupItemVO summaryItem(
            HistoricalGroupItemVO item,
            AccountGroupMetadataSummaryResult summary) {
        if (summary == null) {
            return emptyItem(
                    item.groupJid(), item.subject(), item.membershipState(), SpeechState.ABNORMAL, MISSING_SUMMARY_ERROR);
        }
        HistoricalGroupSelfRole selfRole = HistoricalGroupSelfRole.fromProtocolValue(summary.selfRole());
        RoleCategory roleCategory = roleCategory(selfRole);
        SpeechState speechState = speechState(summary, selfRole);
        return new HistoricalGroupItemVO(
                item.groupJid(),
                firstText(summary.subject(), item.subject()),
                item.membershipState(),
                roleCategory,
                selfRole,
                speechState,
                summary.memberSize(),
                summary.announceOnly(),
                summary.error());
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

    private static SpeechState speechState(
            AccountGroupMetadataSummaryResult summary,
            HistoricalGroupSelfRole selfRole) {
        if (!summary.success() || summary.stateAbnormal()) {
            return SpeechState.ABNORMAL;
        }
        if (Boolean.FALSE.equals(summary.announceOnly())) {
            return SpeechState.NORMAL;
        }
        if (!Boolean.TRUE.equals(summary.announceOnly()) || selfRole == null) {
            return SpeechState.ABNORMAL;
        }
        return selfRole == HistoricalGroupSelfRole.MEMBER
                ? SpeechState.CANNOT_SPEAK
                : SpeechState.ADMIN_CAN_SPEAK;
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
            boolean allowed = accountCanOperate && !self && !owner;
            String disabledReason = allowed ? null
                    : owner ? "群主不能被降级或踢出"
                    : self ? "操作账号本人不能被降级或踢出"
                    : globalDisabledReason;
            members.add(new HistoricalGroupDetailVO.Member(
                    participant.jid().trim(),
                    phone,
                    self,
                    owner,
                    owner || Boolean.TRUE.equals(participant.admin()),
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
                    account.protocolAccountId(), groupJid, actionable, action);
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

    private static String requireBaselineGroup(BaselineSnapshot baseline, String groupJid) {
        String targetJid = blankToNull(groupJid);
        if (targetJid == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群 JID 不能为空");
        }
        if (!baseline.groupJids().contains(targetJid)) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "目标群不属于操作账号 baseline: " + targetJid);
        }
        return targetJid;
    }

    private static HistoricalGroupItemVO emptyItem(
            String groupJid,
            String subject,
            HistoricalGroupMembershipState membershipState,
            SpeechState speechState,
            String error) {
        return new HistoricalGroupItemVO(
                groupJid, subject, membershipState, null, null, speechState, null, null, error);
    }

    private BaselineSnapshot loadBaseline(Long accountId) {
        AccountGroupBaselineRow row = membershipMapper.selectAccountBaselineRow(accountId);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "操作账号 baseline 不存在: " + accountId);
        }
        return new BaselineSnapshot(
                readGroupJids(row.getBaselineGroupJidsJson()),
                readSubjects(row.getBaselineGroupSubjectsJson()));
    }

    private List<String> readGroupJids(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<>() {
            });
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            Set<String> normalized = new LinkedHashSet<>();
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    normalized.add(value.trim());
                }
            }
            return List.copyOf(normalized);
        } catch (JsonProcessingException ex) {
            throw baselineJsonException(ex);
        }
    }

    private Map<String, String> readSubjects(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> subjects = objectMapper.readValue(json, new TypeReference<>() {
            });
            if (subjects == null || subjects.isEmpty()) {
                return Map.of();
            }
            Map<String, String> normalized = new LinkedHashMap<>();
            subjects.forEach((groupJid, subject) -> {
                String normalizedJid = blankToNull(groupJid);
                String normalizedSubject = blankToNull(subject);
                if (normalizedJid != null && normalizedSubject != null) {
                    normalized.putIfAbsent(normalizedJid, normalizedSubject);
                }
            });
            return Map.copyOf(normalized);
        } catch (JsonProcessingException ex) {
            throw baselineJsonException(ex);
        }
    }

    private static BusinessException baselineJsonException(JsonProcessingException ex) {
        return new BusinessException(
                ErrorCode.VALIDATION,
                "操作账号 baseline JSON 解析失败: " + ex.getOriginalMessage());
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

    private record BaselineSnapshot(List<String> groupJids, Map<String, String> subjects) {
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
}
