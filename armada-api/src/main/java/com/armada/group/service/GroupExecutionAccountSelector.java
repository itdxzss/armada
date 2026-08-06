package com.armada.group.service;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.Optional;
import java.util.List;
import org.springframework.stereotype.Component;

/** 为群实时读写选择在线、仍在群内的执行账号。 */
@Component
public final class GroupExecutionAccountSelector {

    private final AccountGroupMembershipMapper mapper;

    public GroupExecutionAccountSelector(AccountGroupMembershipMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 查找执行账号;SQL 优先管理员,再按最近在群时间排序。
     *
     * @param groupLinkId 群链接 ID
     * @return 可用账号
     */
    public Optional<GroupExecutionAccount> find(Long groupLinkId) {
        return Optional.ofNullable(mapper.selectGroupExecutionAccount(
                groupLinkId, AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL));
    }

    /**
     * 要求存在执行账号。
     *
     * @param groupLinkId 群链接 ID
     * @return 可用账号
     * @throws BusinessException 无可用账号时抛出
     */
    public GroupExecutionAccount require(Long groupLinkId) {
        return find(groupLinkId).orElseThrow(() -> new BusinessException(
                ErrorCode.GROUP_EXECUTOR_UNAVAILABLE,
                "没有在线且仍在该群内的账号"));
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
}
