package com.armada.platform.protocol.exception;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;

/**
 * 上下线命令因账号注销生命周期被拒绝；仅在 outbox 尚未写入时抛出。
 *
 * <p>调用方必须先完成当前事务回滚，才可排除这些账号并重新提交其他账号。</p>
 */
public class ProtocolAccountCommandRejectedException extends BusinessException {

    /** 本次上下线批次中被生命周期保护拒绝的账号 ID，不携带号码或凭据。 */
    private final List<Long> accountIds;

    /**
     * 创建可按账号隔离的受理失败。
     *
     * @param accountIds 已确认被拒绝的账号 ID
     */
    public ProtocolAccountCommandRejectedException(List<Long> accountIds) {
        super(ErrorCode.CONFLICT, "账号已被一次性建群任务预留或进入永久注销流程");
        this.accountIds = List.copyOf(accountIds);
    }

    /**
     * 返回被拒绝的账号 ID，供批量编排在回滚后隔离失败账号。
     *
     * @return 不可变账号 ID 列表
     */
    public List<Long> getAccountIds() {
        return accountIds;
    }
}
