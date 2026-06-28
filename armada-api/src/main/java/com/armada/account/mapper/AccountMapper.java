package com.armada.account.mapper;

import com.armada.account.model.dto.AccountQuery;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountDeleteGateRow;
import com.armada.account.model.vo.AccountListVoRow;
import com.armada.account.model.vo.AccountStatsVoRow;
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
     * 按 WA 号查未软删账号(is_active 虚拟列为 1 即 deleted_at IS NULL)。
     * 导入查重/回填场景使用。
     */
    Account selectActiveByWsPhone(@Param("wsPhone") String wsPhone);

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
     * 批量迁移分组:UPDATE account SET account_group_id=#{accountGroupId}, updated_at=#{updatedAt}。
     * 仅更新未软删行。
     *
     * @param ids            账号 ID 列表
     * @param accountGroupId 目标分组 ID
     * @param updatedAt      更新时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int migrateGroup(@Param("ids") List<Long> ids,
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
