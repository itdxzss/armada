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
     * 插入默认状态行(step1:account_state/login_state/risk_status/mute_status 全 NULL=未上报;
     * proxy_failure_count/pull_into_group_count=0;created_at/updated_at 由调用方传入)。
     * useGeneratedKeys 回填 id。
     */
    int insert(AccountState row);

    /**
     * 按 account_id 查状态行(DbTest 验链路用)。
     */
    AccountState selectByAccountId(@Param("accountId") Long accountId);
}
