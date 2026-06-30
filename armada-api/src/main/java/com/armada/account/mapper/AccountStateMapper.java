package com.armada.account.mapper;

import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 账号生命周期状态子表数据访问。tenant_id 由租户行隔离拦截器自动注入。
 */
@Mapper
public interface AccountStateMapper {

    /**
     * 插入默认状态行。
     *
     * <p>step1:account_state/login_state/risk_status/mute_status 全 NULL=未上报;
     * proxy_failure_count/pull_into_group_count=0;created_at/updated_at 由调用方传入。
     * useGeneratedKeys 回填 id。</p>
     *
     * @param row 待插入的账号状态实体(id/tenant_id 由库/拦截器注入)
     * @return 插入行数(正常为 1)
     */
    int insert(AccountState row);

    /**
     * 按 account_id 查状态行。
     *
     * <p>DbTest 验链路使用;生产代码通过 account LEFT JOIN account_state 联表查询。</p>
     *
     * @param accountId 账号主键
     * @return 对应的账号状态行;不存在时返回 null
     */
    AccountState selectByAccountId(@Param("accountId") Long accountId);

    /**
     * 更新账号登录态以及同步元数据。
     *
     * <p>用于 {@code account.state_changed} 普通 ONLINE/OFFLINE/RECONNECTING 等状态回写;
     * 不改 account_state 生命周期字段。</p>
     *
     * @param row 包含 accountId、loginState、lastStateSyncTime、stateSource、updatedAt
     * @return 实际更新行数
     */
    int updateLoginState(AccountState row);

    /**
     * 更新账号登录态、生命周期状态以及同步元数据。
     *
     * <p>用于 NEED_REAUTH、LOGGED_OUT、DEVICE_REMOVED 等必须收敛为封禁/解绑的终态事件。</p>
     *
     * @param row 包含 accountId、loginState、accountState、lastStateSyncTime、stateSource、updatedAt
     * @return 实际更新行数
     */
    int updateLifecycleState(AccountState row);

    /**
     * 更新账号最近一次上线分配的代理展示快照。
     *
     * <p>该快照只供账号列表展示国家、IP 来源、代理地址;不表示当前代理仍被账号占用。</p>
     *
     * @param row 包含 accountId、truthIp、proxyCountry、proxySource、updatedAt
     * @return 实际更新行数
     */
    int updateProxySnapshot(AccountState row);

    /**
     * 重连恢复时把解绑账号恢复为正常。
     *
     * <p>只更新 account_state=5(解绑) 的行,封禁/导出/未上报状态不会被 ONLINE 事件误改。</p>
     *
     * @param row 包含 accountId、accountState=正常、lastStateSyncTime、stateSource、updatedAt
     * @return 实际更新行数
     */
    default int recoverUnboundState(AccountState row) {
        return recoverUnboundStateInternal(row, AccountStateCode.UNBOUND);
    }

    /**
     * 重连恢复时把解绑账号恢复为正常的 SQL 实现。
     *
     * @param row          包含 accountId、accountState=正常、lastStateSyncTime、stateSource、updatedAt
     * @param unboundState 解绑状态码,用于 WHERE 限定
     * @return 实际更新行数
     */
    int recoverUnboundStateInternal(@Param("row") AccountState row, @Param("unboundState") int unboundState);

    /**
     * 更新封号原因。
     *
     * <p>NEED_REAUTH + rawCode=403 收敛为封禁时写入简短原因码,便于列表排查。</p>
     *
     * @param row 包含 accountId、blockReason、updatedAt
     * @return 实际更新行数
     */
    int updateBlockReason(AccountState row);
}
