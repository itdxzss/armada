package com.armada.account.mapper;

import com.armada.account.model.dto.AccountImportQuery;
import com.armada.account.model.entity.AccountImportBatch;
import com.armada.account.model.vo.AccountImportBatchVoRow;
import java.util.List;
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

    /**
     * 按筛选条件统计批次总数(SQL 下推,与 selectPage 共享 filter 片段)。
     *
     * @param query 批次列表查询参数
     * @return 符合条件的批次总数
     */
    long countPage(AccountImportQuery query);

    /**
     * 按筛选条件分页查询批次列表(LEFT JOIN account_group 取组名)。
     *
     * @param query 批次列表查询参数(含 offset / pageSize)
     * @return 当前页批次 VoRow 列表
     */
    List<AccountImportBatchVoRow> selectPage(AccountImportQuery query);
}
