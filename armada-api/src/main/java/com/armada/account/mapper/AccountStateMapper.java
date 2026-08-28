package com.armada.account.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.armada.account.model.AccountProxyFailedRecoveryCandidate;
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
     * 按 account_id 锁定状态行并返回当前时间水位。
     *
     * <p>账号状态事件必须在同一事务内先调用本方法再更新，防止旧事件完成 Java 水位检查后，
     * 被并发的新事件提交并反向覆盖。调用方不得在事务外使用。</p>
     *
     * @param tenantId  租户 ID
     * @param accountId 账号主键
     * @return 已加排他锁的账号状态行；不存在时返回 null
     */
    @InterceptorIgnore(tenantLine = "true")
    AccountState selectByTenantAndAccountIdForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("accountId") Long accountId);

    /**
     * 批量读取账号状态行。
     *
     * <p>用于批量抢登前的全量状态校验,调用方必须确认返回行数与请求账号数一致。</p>
     *
     * @param accountIds 账号主键列表
     * @return 状态行列表
     */
    List<AccountState> selectByAccountIds(@Param("accountIds") List<Long> accountIds);

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
     * 用户手动上线前从未上报或离线状态原子预占为待上线。
     *
     * <p>该条件更新必须和代理分配、快照写入、outbox 入队处在同一外层事务中。
     * 返回行数不足表示并发请求或登录态已变化，调用方必须整体回滚。</p>
     *
     * @param accountIds 本批待上线账号 ID
     * @param updatedAt 更新时间和本地乱序水位(epoch 毫秒)
     * @return 实际预占行数
     */
    default int claimPendingOnline(List<Long> accountIds, long updatedAt) {
        if (accountIds == null || accountIds.isEmpty()) {
            return 0;
        }
        return claimPendingOnlineInternal(
                accountIds,
                AccountLoginStateCode.OFFLINE,
                AccountLoginStateCode.PENDING_ONLINE,
                STATE_SOURCE_OUTBOX,
                updatedAt);
    }

    /**
     * 用户手动上线登录态条件预占的 SQL 实现。
     *
     * @param accountIds 本批待上线账号 ID
     * @param offlineLoginState 离线登录态码
     * @param pendingLoginState 待上线登录态码
     * @param stateSource 状态来源
     * @param updatedAt 更新时间和本地乱序水位(epoch 毫秒)
     * @return 实际预占行数
     */
    int claimPendingOnlineInternal(@Param("accountIds") List<Long> accountIds,
                                   @Param("offlineLoginState") int offlineLoginState,
                                   @Param("pendingLoginState") int pendingLoginState,
                                   @Param("stateSource") String stateSource,
                                   @Param("updatedAt") long updatedAt);

    /**
     * 批量更新账号控制面期望登录状态。
     *
     * <p>该字段只由显式上线/下线命令修改，协议状态事件和自动恢复不得覆盖。</p>
     *
     * @param accountIds 账号 ID 列表
     * @param desiredLoginState 期望登录状态码
     * @param updatedAt 更新时间(epoch 毫秒)
     * @return 实际更新行数
     */
    default int updateDesiredLoginState(List<Long> accountIds, int desiredLoginState, long updatedAt) {
        if (accountIds == null || accountIds.isEmpty()) {
            return 0;
        }
        return updateDesiredLoginStateInternal(accountIds, desiredLoginState, updatedAt);
    }

    int updateDesiredLoginStateInternal(@Param("accountIds") List<Long> accountIds,
                                        @Param("desiredLoginState") int desiredLoginState,
                                        @Param("updatedAt") long updatedAt);

    /**
     * C 事务开始时原子抢占 PROXY_FAILED 恢复资格。
     *
     * <p>只有仍处于 OFFLINE/PROXY_FAILED 的账号能从离线变为待上线；抢占、代理分配、快照和 outbox
     * 处在同一事务，任一步失败都会整体回滚，恢复为可继续补偿的 PROXY_FAILED。</p>
     */
    default int claimProxyFailedReonline(Long accountId, long updatedAt) {
        if (accountId == null) {
            return 0;
        }
        return claimProxyFailedReonlineInternal(
                accountId,
                AccountLoginStateCode.OFFLINE,
                AccountLoginStateCode.PENDING_ONLINE,
                AccountLoginStateCode.OFFLINE,
                "PROXY_FAILED",
                STATE_SOURCE_OUTBOX,
                updatedAt);
    }

    int claimProxyFailedReonlineInternal(@Param("accountId") Long accountId,
                                         @Param("offlineLoginState") int offlineLoginState,
                                         @Param("pendingLoginState") int pendingLoginState,
                                         @Param("desiredOfflineState") int desiredOfflineState,
                                         @Param("expectedStateSource") String expectedStateSource,
                                         @Param("targetStateSource") String targetStateSource,
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
     * 同时更新登录态和账号业务状态。
     *
     * <p>用于抢登中账号的状态保持:ONLINE 只改登录态为在线但账号状态仍保持抢登中;
     * 用户手动离线时把抢登中回落为被抢登。</p>
     *
     * @param row 包含 accountId、loginState、accountState、lastStateSyncTime、stateSource、updatedAt
     * @return 实际更新行数
     */
    int updateLoginAndAccountState(AccountState row);

    /**
     * 将被抢登账号批量标记为抢登中。
     *
     * <p>该更新带 expectedState 与 mute_status 条件,避免前端并发选择或禁言状态变化导致误抢登。</p>
     *
     * @param accountIds     账号主键列表
     * @param expectedState  允许转换的原账号状态
     * @param targetState    抢登中目标状态
     * @param updatedAt      更新时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int markTakingOverByAccountIds(@Param("accountIds") List<Long> accountIds,
                                   @Param("expectedState") Integer expectedState,
                                   @Param("targetState") Integer targetState,
                                   @Param("updatedAt") Long updatedAt);

    /**
     * 更新账号登录态、生命周期状态以及同步元数据。
     *
     * <p>用于 NEED_REAUTH、LOGGED_OUT、DEVICE_REMOVED 等必须收敛为封禁/解绑的终态事件。</p>
     *
     * @param row 包含 accountId、loginState、accountState、lastStateSyncTime、stateSource、updatedAt;
     *            非正常 accountState 会写入 invalidated_at,恢复正常时清空
     * @return 实际更新行数
     */
    int updateLifecycleState(AccountState row);

    /**
     * 回写账号通讯录计数。仅供通讯录同步服务调用，其他地方不得直写这两列。
     *
     * @param accountId 账号 ID
     * @param namedNum 通讯录有名字联系人数
     * @param mutualNum 双向好友数
     * @param updatedAt 更新时间（epoch 毫秒）
     * @return 受影响行数
     */
    int updateContactCounts(@Param("accountId") Long accountId,
                            @Param("namedNum") int namedNum,
                            @Param("mutualNum") int mutualNum,
                            @Param("updatedAt") long updatedAt);

    /**
     * 批量更新账号最近一次上线分配的代理展示快照。
     *
     * <p>不同账号的真实出口、国家和来源均不同，底层使用 UPDATE JOIN 映射字段，
     * 调用方按 100 条以内分片。</p>
     *
     * @param rows 包含 accountId、truthIp、proxyCountry、proxySource、updatedAt 的快照行
     * @return 实际更新行数
     */
    default int updateProxySnapshots(List<AccountState> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        return updateProxySnapshotsInternal(rows);
    }

    /**
     * 批量代理快照 UPDATE JOIN 的 SQL 实现。
     *
     * @param rows 账号代理展示快照行
     * @return 实际更新行数
     */
    int updateProxySnapshotsInternal(@Param("rows") List<AccountState> rows);

    /**
     * 协议回传 ONLINE 时把可恢复的账号生命周期状态收敛为正常。
     *
     * <p>只更新未上报、待上线、解绑状态,封禁/导出等终态不会被 ONLINE 事件误改。</p>
     *
     * @param row 包含 accountId、accountState=正常、lastStateSyncTime、stateSource、updatedAt;
     *            同时清空 invalidated_at
     * @return 实际更新行数
     */
    default int markOnlineNormalState(AccountState row) {
        return markOnlineNormalStateInternal(row, AccountStateCode.NEW, AccountStateCode.UNBOUND);
    }

    /**
     * 协议回传 ONLINE 时把可恢复生命周期状态收敛为正常的 SQL 实现。
     *
     * @param row          包含 accountId、accountState=正常、lastStateSyncTime、stateSource、updatedAt;
     *                     同时清空 invalidated_at
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

    /**
     * 跨租户扫描仍处于 OFFLINE/PROXY_FAILED 且超过即时恢复宽限期的账号。
     *
     * <p>只读账号和状态事实，不加行锁；多实例重复扫描由 C 事务的条件 UPDATE 去重。</p>
     */
    @InterceptorIgnore(tenantLine = "true")
    List<AccountProxyFailedRecoveryCandidate> selectProxyFailedRecoveryCandidates(
            @Param("offlineLoginState") int offlineLoginState,
            @Param("stateSource") String stateSource,
            @Param("desiredOfflineState") int desiredOfflineState,
            @Param("eligibleBefore") long eligibleBefore,
            @Param("limit") int limit);
}
