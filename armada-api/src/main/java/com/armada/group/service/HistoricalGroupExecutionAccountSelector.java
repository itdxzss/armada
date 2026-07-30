package com.armada.group.service;

import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.model.vo.GroupExecutionAccount;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.stereotype.Component;

/** 为历史群详情和写操作选择同账号组内的在线管理员。 */
@Component
public final class HistoricalGroupExecutionAccountSelector {

    private final AccountGroupMembershipMapper mapper;

    /**
     * 创建历史群执行账号选择器。
     *
     * @param mapper 账号群关系数据访问
     */
    public HistoricalGroupExecutionAccountSelector(AccountGroupMembershipMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 校验群属于账号组历史范围并选择在线群主/管理员。
     *
     * @param accountGroupId 账号组 ID
     * @param groupJid WhatsApp 群 JID
     * @return 可执行账号
     */
    public GroupExecutionAccount require(Long accountGroupId, String groupJid) {
        if (accountGroupId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号组 ID 不能为空");
        }
        String normalizedJid = normalize(groupJid);
        if (normalizedJid == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "群 JID 不能为空");
        }
        if (!mapper.existsHistoricalGroupByAccountGroup(accountGroupId, normalizedJid)) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "目标群不属于账号组历史群: " + normalizedJid);
        }
        GroupExecutionAccount account = mapper.selectHistoricalGroupExecutionAccount(
                accountGroupId,
                normalizedJid);
        if (account == null) {
            throw new BusinessException(
                    ErrorCode.GROUP_EXECUTOR_UNAVAILABLE,
                    "没有在线且仍在群内的群主或管理员");
        }
        return account;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
