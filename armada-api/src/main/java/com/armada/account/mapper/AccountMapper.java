package com.armada.account.mapper;

import com.armada.account.model.entity.Account;
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
}
