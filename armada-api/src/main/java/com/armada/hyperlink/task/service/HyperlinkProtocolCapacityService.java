package com.armada.hyperlink.task.service;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.stereotype.Service;

/** 协议节点容量的唯一计算与启用门禁；协议数复用账号匹配上下文的事实源。 */
@Service
public class HyperlinkProtocolCapacityService {
    public static final int ACCOUNTS_PER_PROTOCOL = 15;

    private final HyperlinkAccountCandidateSelector accountSelector;

    public HyperlinkProtocolCapacityService(HyperlinkAccountCandidateSelector accountSelector) {
        this.accountSelector = accountSelector;
    }

    /** 当前租户可承载的执行账号上限。 */
    public long maxExecutingAccounts() {
        return (long) accountSelector.protocolCount() * ACCOUNTS_PER_PROTOCOL;
    }

    /** 真正启用任务前重检；边界值允许，零协议不能启用任何正并发任务。 */
    public void requireSufficient(int requestedAccounts) {
        long capacity = maxExecutingAccounts();
        if (requestedAccounts > capacity) {
            throw new BusinessException(ErrorCode.HYPERLINK_PROTOCOL_CAPACITY_INSUFFICIENT,
                    "协议容量不足：请求执行账号数 " + requestedAccounts + "，当前容量 " + capacity);
        }
    }
}
