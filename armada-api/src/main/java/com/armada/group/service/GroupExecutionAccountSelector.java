package com.armada.group.service;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.Optional;
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
                groupLinkId, AccountLoginStateCode.ONLINE));
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
}
