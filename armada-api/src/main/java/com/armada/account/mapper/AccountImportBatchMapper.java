package com.armada.account.mapper;

import com.armada.account.model.entity.AccountImportBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 账号导入批次数据访问。tenant_id 由租户行隔离拦截器自动注入。
 */
@Mapper
public interface AccountImportBatchMapper {

    /**
     * 插入批次行(id/tenant_id 由库/拦截器注入;时间由调用方显式传入)。
     * useGeneratedKeys 回填 id。
     */
    int insert(AccountImportBatch row);

    /**
     * 按主键查批次行。
     */
    AccountImportBatch selectById(@Param("id") Long id);
}
