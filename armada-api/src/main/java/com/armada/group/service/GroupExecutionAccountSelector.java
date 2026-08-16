package com.armada.group.service;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 为群实时读写选择在线、仍在群内的执行账号。 */
@Component
public final class GroupExecutionAccountSelector {

    private static final int MAX_RETRY_CANDIDATES = 4;
    private static final int MAX_ADMIN_DISCOVERY_CANDIDATES = 500;

    private final AccountGroupMembershipMapper mapper;

    public GroupExecutionAccountSelector(AccountGroupMembershipMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 查找执行账号;SQL 优先管理员,再按最近在群时间排序。
     *
     * @param groupLinkId 群链接 ID
     * @param completedAttempts 已完成尝试数，用于稳定轮换候选
     * @return 可用账号
     */
    public Optional<GroupExecutionAccount> find(Long groupLinkId, int completedAttempts) {
        return candidateAt(findCandidates(groupLinkId), completedAttempts);
    }

    /**
     * 返回有界的在线在群候选，保持群管理员、最近在群时间的数据库排序。
     *
     * @param groupLinkId 群入口 ID
     * @return 可用于实时群查询的候选账号
     */
    public List<GroupExecutionAccount> findCandidates(Long groupLinkId) {
        if (groupLinkId == null) {
            return List.of();
        }
        List<GroupExecutionAccount> candidates = mapper.selectGroupExecutionAccounts(
                groupLinkId,
                AccountLoginStateCode.ONLINE,
                AccountStateCode.NORMAL,
                MAX_RETRY_CANDIDATES);
        return candidates == null ? List.of() : List.copyOf(candidates);
    }

    /**
     * 选择必须具备群管理员角色的执行账号。
     *
     * <p>刷新群邀请链接是管理员专属操作。以 account_group_membership.is_admin 作为准入口径,
     * 与群组列表"可用管理员"列一致;取不到时调用方直接判该项失败并给出"没有可用管理员账号",
     * 不再发出注定被协议层拒绝的调用。角色快照过期的残余情况由协议层兜底拒绝。</p>
     *
     * @param groupLinkId 群入口 ID
     * @return 最近在群的管理员账号;群内无在线管理员时为空
     */
    public Optional<GroupExecutionAccount> findAdmin(Long groupLinkId) {
        if (groupLinkId == null) {
            return Optional.empty();
        }
        List<GroupExecutionAccount> candidates = mapper.selectGroupAdminExecutionAccounts(
                groupLinkId,
                AccountLoginStateCode.ONLINE,
                AccountStateCode.NORMAL,
                1);
        return candidates == null || candidates.isEmpty()
                ? Optional.empty()
                : Optional.of(candidates.get(0));
    }

    /**
     * 根据新鲜 metadata 已确认的管理员手机号选择在线正常账号。
     *
     * <p>本轮完整 metadata 已经证明这些号码属于当前管理员，因此不要求账号群关系先落库；
     * 这能覆盖新群首次同步中“先读邀请码、后写关系快照”的时序。只接受可规范化为数字的
     * 可信手机号；LID 不得作为手机号参与匹配。</p>
     *
     * @param groupLinkId 群入口 ID
     * @param adminPhones 新鲜 metadata 确认的管理员手机号
     * @param completedAttempts 已完成尝试数，用于稳定轮换候选
     * @return 可执行邀请码读取的账号
     */
    public Optional<GroupExecutionAccount> findAdminByPhones(
            Long groupLinkId,
            List<String> adminPhones,
            int completedAttempts) {
        List<String> normalizedPhones = adminPhones == null
                ? List.of()
                : adminPhones.stream()
                        .map(GroupExecutionAccountSelector::normalizePhone)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .sorted()
                        .toList();
        if (normalizedPhones.isEmpty()) {
            return Optional.empty();
        }
        return candidateAt(mapper.selectGroupExecutionAccountsByPhones(
                groupLinkId,
                normalizedPhones,
                AccountLoginStateCode.ONLINE,
                AccountStateCode.NORMAL,
                MAX_RETRY_CANDIDATES), completedAttempts);
    }

    /**
     * 要求存在执行账号。
     *
     * @param groupLinkId 群链接 ID
     * @return 可用账号
     * @throws BusinessException 无可用账号时抛出
     */
    public GroupExecutionAccount require(Long groupLinkId) {
        return find(groupLinkId, 0).orElseThrow(() -> new BusinessException(
                ErrorCode.GROUP_EXECUTOR_UNAVAILABLE,
                "没有在线且仍在该群内的账号"));
    }

    /**
     * 要求目标群已确认的群主账号当前在线、正常且仍在群内。
     *
     * <p>群权限设置不能回退到其它管理员或普通成员，避免本地角色快照滞后时把写操作
     * 发给不具备真实权限的账号。</p>
     *
     * @param groupLinkId 群链接 ID
     * @return 对应 WhatsApp 群的群主账号
     * @throws BusinessException 群主身份未知或群主账号当前不可执行时抛出
     */
    public GroupExecutionAccount requireOwner(Long groupLinkId) {
        GroupExecutionAccount owner = groupLinkId == null
                ? null
                : mapper.selectGroupOwnerExecutionAccount(
                        groupLinkId,
                        AccountLoginStateCode.ONLINE,
                        AccountStateCode.NORMAL);
        if (owner == null) {
            throw new BusinessException(
                    ErrorCode.GROUP_EXECUTOR_UNAVAILABLE,
                    "没有在线且仍在该群内的群主账号");
        }
        return owner;
    }

    /**
     * 查询可为普通拉群任务管理员提权的当前租户群管理员候选。
     *
     * @param tenantId 当前租户 ID
     * @param groupJid 目标群 JID
     * @param managerAccountId 待提权的任务管理员账号 ID
     * @return 保持群主、最近在群时间和账号 ID 排序的候选
     */
    public List<GroupExecutionAccount> findPullTaskAdminPromoterCandidates(
            Long tenantId, String groupJid, Long managerAccountId) {
        return mapper.selectPullTaskAdminPromoterCandidatesByTenant(
                tenantId, groupJid, managerAccountId);
    }

    /** 返回不依赖本地管理员标记的定点查询候选，始终限制为协议单次上限。 */
    public List<GroupExecutionAccount> findPullTaskAdminDiscoveryCandidates(
            Long tenantId, String groupJid, Long managerAccountId) {
        List<GroupExecutionAccount> candidates =
                mapper.selectPullTaskAdminDiscoveryCandidatesByTenant(
                        tenantId, groupJid, managerAccountId,
                        MAX_ADMIN_DISCOVERY_CANDIDATES);
        return candidates == null ? List.of() : candidates.stream()
                .limit(MAX_ADMIN_DISCOVERY_CANDIDATES)
                .toList();
    }

    private static Optional<GroupExecutionAccount> candidateAt(
            List<GroupExecutionAccount> candidates,
            int completedAttempts) {
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        int index = Math.floorMod(completedAttempts, candidates.size());
        return Optional.of(candidates.get(index));
    }

    private static String normalizePhone(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        int at = normalized.indexOf('@');
        if (at >= 0 && !normalized.endsWith("@s.whatsapp.net")) {
            return null;
        }
        String digits = normalized.replaceAll("[^0-9]", "");
        return digits.isBlank() ? null : digits;
    }
}
