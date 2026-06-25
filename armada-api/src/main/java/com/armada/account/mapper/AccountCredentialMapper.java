package com.armada.account.mapper;

import com.armada.account.model.entity.AccountCredential;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 账号自托管凭据数据访问。tenant_id 由租户行隔离拦截器自动注入。
 * 铁律:日志只打 maskPhone+凭据长度,绝不打 creds_json 明文。
 */
@Mapper
public interface AccountCredentialMapper {

    /**
     * 插入凭据行(id/tenant_id 由库/拦截器注入;时间由调用方显式传入)。
     * useGeneratedKeys 回填 id。
     */
    int insert(AccountCredential row);

    /**
     * 按 account_id 查活跃凭据行(deleted_at IS NULL)。
     * 导入/上线场景使用。
     */
    AccountCredential selectByAccountId(@Param("accountId") Long accountId);
}
