package com.armada.account.mapper;

import com.armada.account.model.dto.AccountQuery;
import com.armada.account.model.entity.Account;
import com.armada.account.model.vo.AccountListVoRow;
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
}
