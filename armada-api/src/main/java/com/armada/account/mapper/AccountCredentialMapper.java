package com.armada.account.mapper;

import com.armada.account.model.entity.AccountCredential;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 账号自托管凭据数据访问。tenant_id 由租户行隔离拦截器自动注入。
 * 铁律:日志只打 maskPhone+凭据长度,绝不打 creds_json 明文。
 */
@Mapper
public interface AccountCredentialMapper {

    /**
     * 插入凭据行。
     *
     * <p>id/tenant_id 由库/拦截器注入;时间由调用方显式传入。
     * useGeneratedKeys 回填 id。</p>
     *
     * @param row 待插入的凭据实体(creds_json 不得打印到日志)
     * @return 插入行数(正常为 1)
     */
    int insert(AccountCredential row);

    /**
     * 按 account_id 查活跃凭据行(deleted_at IS NULL)。
     *
     * <p>导入/上线场景使用;查协议层下发 creds_json 喂给协议层 connect 接口。</p>
     *
     * @param accountId 账号主键
     * @return 活跃凭据行;不存在或已软删时返回 null
     */
    AccountCredential selectByAccountId(@Param("accountId") Long accountId);

    /**
     * 按 account_id 批量查活跃凭据行。
     *
     * <p>批量上线使用,避免每个账号单独打一次凭据查询。creds_json 仍然只作为协议命令输入,
     * 不允许写日志。</p>
     *
     * @param accountIds 账号主键列表
     * @return 活跃凭据行列表;缺失或已软删凭据不会返回
     */
    List<AccountCredential> selectByAccountIds(@Param("accountIds") List<Long> accountIds);
}
