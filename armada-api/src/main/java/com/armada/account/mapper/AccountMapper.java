package com.armada.account.mapper;

import com.armada.account.model.dto.AccountBatchTargetQuery;
import com.armada.account.model.dto.AccountQuery;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountDeleteGateRow;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.vo.AccountBatchPreviewRow;
import com.armada.account.model.vo.AccountBatchTargetRow;
import com.armada.account.model.vo.AccountGroupSyncCandidate;
import com.armada.account.model.vo.AccountIpRegionRow;
import com.armada.account.model.vo.AccountListVoRow;
import com.armada.account.model.vo.AccountStatsVoRow;
import com.armada.account.model.vo.AccountWsPhoneExportRow;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 账号身份主表数据访问。tenant_id 由租户行隔离拦截器自动注入,SQL 不手写 tenant_id 过滤。
 */
@Mapper
public interface AccountMapper {

    /**
     * 插入新账号(id/tenant_id 由库/拦截器注入;时间由调用方显式传入)。
     * useGeneratedKeys 回填 id。
     */
    int insert(Account row);

    /**
     * 插入由推广配对创建的账号，并写入渠道归因字段。
     *
     * <p>该入口只供推广配对落库使用，避免修改普通导入、后台创建等存量账号的通用 insert SQL。</p>
     *
     * @param row 已完成校验的推广配对账号
     * @return 插入行数（正常为 1）
     */
    int insertPromotionAccount(Account row);

    /**
     * 按 WA 号查未软删账号(is_active 虚拟列为 1 即 deleted_at IS NULL)。
     * 导入查重/回填场景使用。
     */
    Account selectActiveByWsPhone(@Param("wsPhone") String wsPhone);

    /**
     * 跨租户判断手机号是否已归属任一活跃账号。
     *
     * <p>仅供公开推广配对入口做全局身份归属保护；显式绕过租户插件，禁止用于普通账号列表查询。</p>
     */
    @InterceptorIgnore(tenantLine = "true")
    boolean existsActiveByWsPhoneAnyTenant(@Param("wsPhone") String wsPhone);

    /**
     * 按 ID 查未软删账号。
     *
     * <p>上线等按账号主键发起的业务入口使用;tenant_id 由租户拦截器注入。</p>
     *
     * @param id 账号主键
     * @return 活跃账号;不存在或已软删时返回 null
     */
    Account selectActiveById(@Param("id") Long id);

    /**
     * 从指定账号分组随机选择在线正常且协议身份完整的活跃账号。
     *
     * <p>状态筛选全部下推 SQL，且故意不关联任何任务占用表；tenant_id 由租户拦截器注入。</p>
     *
     * @param groupId 账号分组 ID
     * @param normalAccountState 正常账号生命周期状态码
     * @param onlineLoginState 在线登录状态码
     * @param riskAllowed 风险允许状态码；未上报风险同样允许
     * @return 随机候选；无候选时返回 null，由 Service 转为 Optional.empty
     */
    Account selectRandomOnlineNormalByGroupId(
            @Param("groupId") Long groupId,
            @Param("normalAccountState") int normalAccountState,
            @Param("onlineLoginState") int onlineLoginState,
            @Param("riskAllowed") int riskAllowed);

    /**
     * 从指定分组随机选择在线正常且协议身份完整的 Web 拉手账号。
     *
     * @param groupId           账号分组 ID
     * @param normalAccountState 正常账号生命周期状态码
     * @param onlineLoginState  在线登录状态码
     * @param riskAllowed       风险允许状态码
     * @param webProtocolId     Web 协议标识
     * @return 随机 Web 候选；无候选时返回 null
     */
    Account selectRandomOnlineNormalWebByGroupId(
            @Param("groupId") Long groupId,
            @Param("normalAccountState") int normalAccountState,
            @Param("onlineLoginState") int onlineLoginState,
            @Param("riskAllowed") int riskAllowed,
            @Param("webProtocolId") String webProtocolId);

    /**
     * 按协议账号句柄查未软删账号。
     *
     * <p>协议层 Kafka 事件的 accountId 对应本表 protocol_account_id,状态回写入口用它反查账号主键。</p>
     *
     * @param protocolAccountId 协议账号句柄
     * @return 活跃账号;不存在或已软删时返回 null
     */
    Account selectActiveByProtocolAccountId(@Param("protocolAccountId") String protocolAccountId);

    /**
     * 按 ID 批量查未软删账号。
     *
     * <p>批量上线等场景使用;tenant_id 由租户拦截器注入。</p>
     *
     * @param ids 账号主键列表
     * @return 活跃账号列表;不存在或已软删账号不会返回
     */
    List<Account> selectActiveByIds(@Param("ids") List<Long> ids);

    /**
     * 批量读取未软删账号的当前登录态。
     *
     * <p>只投影 {@link AccountState#getAccountId()} 与 {@link AccountState#getLoginState()}；
     * 已软删账号不返回，状态未上报时 loginState 为 null。</p>
     *
     * @param ids 账号主键列表
     * @return 当前租户的活跃账号登录态
     */
    List<AccountState> selectActiveLoginStatesByIds(@Param("ids") List<Long> ids);

    /**
     * 按完整 WA 号码批量查询未软删账号。
     *
     * <p>该查询服务固定账号协议寻址，不连接状态或群关系表，离线、禁言等运行时事实不应提前排除。</p>
     *
     * @param wsPhones 已去空、去重的完整 WA 号码
     * @return 当前租户可见的活跃账号；数据库返回顺序不作为业务顺序
     */
    List<Account> selectActiveByWsPhones(@Param("wsPhones") List<String> wsPhones);

    /**
     * 查询指定账号中未软删除的 WS 号码，不限制账号状态。
     *
     * <p>调用方保证 IDs 非空并分片；tenant_id 由租户拦截器注入。</p>
     *
     * @param ids 当前租户前端所选账号 ID
     * @return 按账号 ID 升序排列的最小导出字段
     */
    List<AccountWsPhoneExportRow> selectWsPhonesByIds(@Param("ids") List<Long> ids);

    /**
     * 在指定账号中筛选当前在线账号 ID。
     *
     * @param ids              账号主键列表
     * @param onlineLoginState 在线状态码
     * @return 当前 login_state=ONLINE 的活跃账号 ID
     */
    List<Long> selectOnlineAccountIdsByIds(@Param("ids") List<Long> ids,
                                           @Param("onlineLoginState") int onlineLoginState);

    /**
     * 查询账号导入时选择的 IP 国家。
     *
     * <p>上线分配代理使用该值作为国家优先级来源。若账号没有成功导入明细,调用方按无偏好处理。</p>
     *
     * @param ids           账号主键列表
     * @param successResult 导入明细成功状态码
     * @return 每个可追溯导入批次的账号 IP 国家
     */
    List<AccountIpRegionRow> selectIpRegionsByAccountIds(@Param("ids") List<Long> ids,
                                                         @Param("successResult") int successResult);

    /**
     * 跨租户扫描账号当前群同步候选。
     *
     * <p>调度线程没有 HTTP 租户上下文,因此关闭租户拦截器并在 SQL 内显式按 tenant_id
     * 连接 account/account_state/account_group_baseline。已拍 baseline 的在线正常账号按 baseline 做差集;
     * 不启用 baseline 的在线正常账号直接同步。待拍账号由营销账号树实时捕获 baseline,不由定时任务抢拍。</p>
     *
     * @param limit                 本轮最大候选数
     * @param onlineLoginState      在线登录态码
     * @param normalAccountState    正常账号状态码
     * @param baselineCapturedState 已拍群基线状态码
     * @return 可发起协议层 listParticipating 的候选账号
     */
    @InterceptorIgnore(tenantLine = "true")
    List<AccountGroupSyncCandidate> selectGroupSyncCandidates(
            @Param("limit") int limit,
            @Param("onlineLoginState") int onlineLoginState,
            @Param("normalAccountState") int normalAccountState,
            @Param("baselineCapturedState") int baselineCapturedState);

    /**
     * 标记账号当前群同步命令已入队。
     *
     * <p>该水位只用于后台调度轮转,不覆盖登录前群 baseline JSON。调用方必须先恢复
     * {@link com.armada.shared.tenant.TenantContext},让租户拦截器限制在当前租户内更新。</p>
     *
     * @param accountIds  已成功写入 outbox 的账号 ID
     * @param requestedAt 入队时间(epoch 毫秒)
     * @return 更新行数
     */
    int markGroupSyncRequested(@Param("accountIds") List<Long> accountIds,
                               @Param("requestedAt") long requestedAt);

    /**
     * 按筛选条件统计账号总数(SQL 下推,与 selectPage 共享 filter 片段)。
     *
     * @param query 账号列表查询参数
     * @return 符合条件的账号总数
     */
    long countPage(AccountQuery query);

    /**
     * 按筛选条件分页查询账号列表
     * (account LEFT JOIN account_state LEFT JOIN account_group,状态列 step1 全 NULL)。
     *
     * @param query 账号列表查询参数(含 offset / pageSize)
     * @return 当前页账号 VoRow 列表
     */
    List<AccountListVoRow> selectPage(AccountQuery query);

    /**
     * 预估指定 ID 范围内的活跃账号和批量登录跳过数量。
     *
     * @param ids 当前租户账号 ID，调用方保证非空
     * @return 匹配数和互斥跳过原因聚合
     */
    AccountBatchPreviewRow previewBatchTargetsByIds(@Param("ids") List<Long> ids);

    /**
     * 按账号列表筛选条件预估全部匹配账号和批量登录跳过数量。
     *
     * @param query 已规范化且不使用分页语义的查询条件
     * @return 匹配数和互斥跳过原因聚合
     */
    AccountBatchPreviewRow previewBatchTargetsByQuery(AccountQuery query);

    /**
     * 查询指定 ID 范围内的最小批量编排字段。
     *
     * @param ids 当前租户账号 ID，调用方保证非空
     * @return 活跃账号目标行，按 ID 升序
     */
    List<AccountBatchTargetRow> selectBatchTargetsByIds(@Param("ids") List<Long> ids);

    /**
     * 按列表筛选条件和稳定 ID 游标扫描批量目标。
     *
     * @param query 筛选条件、上一轮 ID 和本轮扫描上限
     * @return 当前游标批次目标行，按 ID 升序
     */
    List<AccountBatchTargetRow> selectBatchTargetsAfterId(AccountBatchTargetQuery query);

    /**
     * 平台级账号统计卡:本租户全量单条聚合 SQL。
     * tenant_id 由租户行隔离拦截器自动注入,SQL 不手写。
     *
     * @return 统计卡聚合行(total/online/offline/banned/risk/assigned)
     */
    AccountStatsVoRow statsSummary();

    /**
     * 批量查删除闸门数据:取 account_state + dispatched_at,供严格口径校验。
     * LEFT JOIN account_state:step1 导入后状态列全 NULL,正常投影。
     *
     * @param ids 账号 ID 列表
     * @return 每个 id 对应一行(id/accountState/dispatchedAt)
     */
    List<AccountDeleteGateRow> selectStatesByIds(@Param("ids") List<Long> ids);

    /**
     * 按账号原始来源分组执行人工迁移条件更新。
     *
     * <p>来源和目标分组必须存在且未被营销任务占用；非空来源分组不得处于拉群任务
     * 资源锁定或释放中。账号已离开预期来源、任一分组状态变化时对应行不会更新。</p>
     *
     * @param ids 待迁移的同一来源分组账号 ID
     * @param sourceGroupId 读取账号时的来源分组 ID；为空表示未分组
     * @param accountGroupId 目标分组 ID
     * @param updatedAt 更新时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int migrateGroupIfAvailable(@Param("ids") List<Long> ids,
                                @Param("sourceGroupId") Long sourceGroupId,
                                @Param("accountGroupId") Long accountGroupId,
                                @Param("updatedAt") long updatedAt);

    /**
     * 批量软删除账号:UPDATE account SET deleted_at=#{deletedAt}。
     * 仅更新未软删行(deleted_at IS NULL)。
     *
     * @param ids       账号 ID 列表
     * @param deletedAt 软删时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int batchSoftDelete(@Param("ids") List<Long> ids, @Param("deletedAt") long deletedAt);
}
