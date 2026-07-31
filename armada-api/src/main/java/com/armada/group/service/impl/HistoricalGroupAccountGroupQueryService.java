package com.armada.group.service.impl;

import com.armada.account.mapper.AccountGroupMapper;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.model.enums.HistoricalGroupMembershipState;
import com.armada.group.model.enums.HistoricalGroupSelfRole;
import com.armada.group.model.enums.RoleCategory;
import com.armada.group.model.enums.SpeechState;
import com.armada.group.model.dto.HistoricalGroupQuery;
import com.armada.group.model.vo.HistoricalGroupAccountPhoneRow;
import com.armada.group.model.vo.HistoricalGroupItemVO;
import com.armada.group.model.vo.HistoricalGroupPageRow;
import com.armada.platform.country.model.vo.CountryReferenceVO;
import com.armada.platform.country.service.CountryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 账号组历史群缓存分页查询与展示字段组装。 */
@Service
public class HistoricalGroupAccountGroupQueryService {

    private static final String INVITE_PREFIX = "https://chat.whatsapp.com/";
    private static final String EXECUTOR_OFFLINE_REASON = "暂无在线群主或管理员";

    private final AccountGroupMapper accountGroupMapper;
    private final AccountGroupMembershipMapper membershipMapper;
    private final CountryService countryService;

    /**
     * 创建账号组历史群查询服务。
     *
     * @param accountGroupMapper 账号组数据访问
     * @param membershipMapper 账号群关系数据访问
     * @param countryService 国家识别服务
     */
    public HistoricalGroupAccountGroupQueryService(
            AccountGroupMapper accountGroupMapper,
            AccountGroupMembershipMapper membershipMapper,
            CountryService countryService) {
        this.accountGroupMapper = accountGroupMapper;
        this.membershipMapper = membershipMapper;
        this.countryService = countryService;
    }

    /**
     * 分页读取账号组历史群,全程只使用本地持久化事实。
     *
     * @param query 账号组和分页参数
     * @return 群维度分页结果
     */
    public PageResult<HistoricalGroupItemVO> list(HistoricalGroupQuery query) {
        Long accountGroupId = requireAccountGroup(query);
        long total = membershipMapper.countHistoricalGroupsByAccountGroup(accountGroupId);
        List<HistoricalGroupPageRow> rows = total == 0
                ? List.of()
                : membershipMapper.selectHistoricalGroupPageByAccountGroup(
                        accountGroupId, query.getOffset(), query.getPageSize());
        List<String> groupJids = rows.stream()
                .map(HistoricalGroupPageRow::getGroupJid)
                .toList();
        Map<String, List<String>> accountPhones = groupJids.isEmpty()
                ? Map.of()
                : accountPhones(
                        rows,
                        membershipMapper.selectHistoricalGroupAccountPhonesByAccountGroup(
                                accountGroupId, groupJids));
        List<String> creators = rows.stream()
                .map(HistoricalGroupPageRow::getOwnerPhone)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        Map<String, CountryReferenceVO> countries = creators.isEmpty()
                ? Map.of()
                : countryService.resolveActiveCountriesByPhoneNumbers(creators);
        List<HistoricalGroupItemVO> items = rows.stream()
                .map(row -> toItem(
                        row,
                        countries.get(row.getOwnerPhone()),
                        accountPhones.getOrDefault(row.getGroupJid(), List.of())))
                .toList();
        return PageResult.of(items, query.getPage(), query.getPageSize(), total);
    }

    private Long requireAccountGroup(HistoricalGroupQuery query) {
        Long accountGroupId = query == null ? null : query.getAccountGroupId();
        if (accountGroupId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号组 ID 不能为空");
        }
        if (accountGroupMapper.selectById(accountGroupId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "账号组不存在: " + accountGroupId);
        }
        return accountGroupId;
    }

    private static HistoricalGroupItemVO toItem(
            HistoricalGroupPageRow row,
            CountryReferenceVO country,
            List<String> accountPhones) {
        HistoricalGroupSelfRole selfRole = selfRole(row);
        boolean operable = Boolean.TRUE.equals(row.getOperable());
        return new HistoricalGroupItemVO(
                row.getGroupJid(),
                row.getSubject(),
                accountPhones,
                inviteLink(row.getInviteCode()),
                country == null ? null : country.iso2(),
                country == null ? null : country.nameZh(),
                country == null ? null : country.flag(),
                row.getGroupCreatedAt(),
                membershipState(row),
                selfRole == null ? null : roleCategory(selfRole),
                selfRole,
                speechState(row.getAnnounceOnly(), selfRole),
                row.getMemberSize(),
                row.getAnnounceOnly(),
                operable,
                operable ? null : EXECUTOR_OFFLINE_REASON,
                null);
    }

    private static HistoricalGroupMembershipState membershipState(HistoricalGroupPageRow row) {
        if (zero(row.getKnownMembershipCount()) == 0) {
            return HistoricalGroupMembershipState.UNVERIFIED;
        }
        return zero(row.getInGroupCount()) > 0
                ? HistoricalGroupMembershipState.CURRENT_IN_GROUP
                : HistoricalGroupMembershipState.CURRENT_NOT_IN_GROUP;
    }

    private static HistoricalGroupSelfRole selfRole(HistoricalGroupPageRow row) {
        if (Boolean.TRUE.equals(row.getOwnerInGroup())) {
            return HistoricalGroupSelfRole.OWNER;
        }
        if (Boolean.TRUE.equals(row.getAdminInGroup())) {
            return HistoricalGroupSelfRole.ADMIN;
        }
        return zero(row.getKnownMembershipCount()) > 0
                ? HistoricalGroupSelfRole.MEMBER
                : null;
    }

    private static RoleCategory roleCategory(HistoricalGroupSelfRole selfRole) {
        return selfRole == HistoricalGroupSelfRole.MEMBER
                ? RoleCategory.MEMBER
                : RoleCategory.ADMIN;
    }

    private static SpeechState speechState(
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

    private static Map<String, List<String>> accountPhones(
            List<HistoricalGroupPageRow> rows,
            List<HistoricalGroupAccountPhoneRow> phoneRows) {
        Map<String, LinkedHashSet<String>> current = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> baseline = new LinkedHashMap<>();
        if (phoneRows != null) {
            for (HistoricalGroupAccountPhoneRow row : phoneRows) {
                if (row.getGroupJid() == null
                        || row.getAccountPhone() == null
                        || row.getAccountPhone().isBlank()) {
                    continue;
                }
                Map<String, LinkedHashSet<String>> target = Boolean.TRUE.equals(row.getCurrentRelation())
                        ? current
                        : baseline;
                target.computeIfAbsent(row.getGroupJid(), ignored -> new LinkedHashSet<>())
                        .add(row.getAccountPhone().trim());
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (HistoricalGroupPageRow row : rows) {
            LinkedHashSet<String> phones = current.get(row.getGroupJid());
            if (phones == null || phones.isEmpty()) {
                phones = baseline.get(row.getGroupJid());
            }
            result.put(row.getGroupJid(), phones == null ? List.of() : List.copyOf(phones));
        }
        return result;
    }

    private static String inviteLink(String inviteCode) {
        return inviteCode == null || inviteCode.isBlank()
                ? null
                : INVITE_PREFIX + inviteCode.trim();
    }

    private static int zero(Integer value) {
        return value == null ? 0 : value;
    }
}
