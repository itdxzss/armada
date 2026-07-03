package com.armada.account.mapper;

import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 账号生命周期状态子表数据访问。tenant_id 由租户行隔离拦截器自动注入。
 */
@Mapper
public interface AccountStateMapper {

    /** Armada 本地 outbox 受理状态来源。 */
    String STATE_SOURCE_OUTBOX = "OUTBOX";

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
     * 将账号登录态标记为待上线。
     *
     * <p>该状态由 Armada 在上线命令写入 outbox 后本地写入,用于 UI 展示“待上线”。
     * 同时更新 last_state_sync_time 作为乱序水位,避免点击上线前的旧协议事件覆盖待上线状态。</p>
     *
     * @param accountIds 账号主键列表
     * @param updatedAt  更新时间和本地乱序水位(epoch 毫秒)
     * @return 实际更新行数
     */
    default int markPendingOnline(List<Long> accountIds, long updatedAt) {
        if (accountIds == null || accountIds.isEmpty()) {
            return 0;
        }
        return markPendingOnlineInternal(accountIds,
                AccountLoginStateCode.PENDING_ONLINE,
                STATE_SOURCE_OUTBOX,
                updatedAt);
    }

    /**
     * 将账号登录态标记为待上线的 SQL 实现。
     *
     * @param accountIds         账号主键列表
     * @param pendingLoginState  待上线登录态码
     * @param stateSource        状态来源
     * @param updatedAt          更新时间和本地乱序水位(epoch 毫秒)
     * @return 实际更新行数
     */
    int markPendingOnlineInternal(@Param("accountIds") List<Long> accountIds,
                                  @Param("pendingLoginState") int pendingLoginState,
                                  @Param("stateSource") String stateSource,
                                  @Param("updatedAt") long updatedAt);

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
     * 协议回传 ONLINE 时把可恢复的账号生命周期状态收敛为正常。
     *
     * <p>只更新未上报、待上线、解绑状态,封禁/导出等终态不会被 ONLINE 事件误改。</p>
     *
     * @param row 包含 accountId、accountState=正常、lastStateSyncTime、stateSource、updatedAt
     * @return 实际更新行数
     */
    default int markOnlineNormalState(AccountState row) {
        return markOnlineNormalStateInternal(row, AccountStateCode.NEW, AccountStateCode.UNBOUND);
    }

    /**
     * 协议回传 ONLINE 时把可恢复生命周期状态收敛为正常的 SQL 实现。
     *
     * @param row          包含 accountId、accountState=正常、lastStateSyncTime、stateSource、updatedAt
     * @param newState     待上线状态码,用于 WHERE 限定
     * @param unboundState 解绑状态码,用于 WHERE 限定
     * @return 实际更新行数
     */
    int markOnlineNormalStateInternal(@Param("row") AccountState row,
                                      @Param("newState") int newState,
                                      @Param("unboundState") int unboundState);

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
