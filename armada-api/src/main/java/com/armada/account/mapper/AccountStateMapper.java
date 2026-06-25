package com.armada.account.mapper;

import com.armada.account.model.entity.AccountState;
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
}
