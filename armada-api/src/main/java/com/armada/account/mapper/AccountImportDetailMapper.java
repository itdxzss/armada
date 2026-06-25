package com.armada.account.mapper;

import com.armada.account.model.dto.AccountImportDetailQuery;
import com.armada.account.model.entity.AccountImportDetail;
import com.armada.account.model.vo.AccountImportDetailVoRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 账号导入明细数据访问。tenant_id 由租户行隔离拦截器自动注入。
 */
@Mapper
public interface AccountImportDetailMapper {

    /**
     * 批量插入明细行(&lt;foreach&gt; 多值 INSERT)。
     * tenant_id 由拦截器注入,不手写。
     */
    int batchInsert(@Param("rows") List<AccountImportDetail> rows);

    /**
     * 按批次 ID 和结果过滤器统计明细总数(SQL 下推)。
     *
     * @param query 明细查询参数(batchId 必传,filter = all/success/fail)
     * @return 符合条件的明细总数
     */
    long countByBatch(AccountImportDetailQuery query);

    /**
     * 按批次 ID 分页查询明细列表(JOIN account_import_batch 取 groupName)。
     *
     * @param query 明细查询参数(含 offset / pageSize / filter)
     * @return 当前页明细 VoRow 列表
     */
    List<AccountImportDetailVoRow> selectPageByBatch(AccountImportDetailQuery query);

    /**
     * 按批次 ID 查全部明细(不分页),用于 CSV 导出。
     *
     * @param batchId 批次 ID
     * @param scope   结果范围:all=全部;success=只成功(parse_result=1);fail=只失败(parse_result IN 2,3,4)
     * @return 符合条件的明细 VoRow 列表
     */
    List<AccountImportDetailVoRow> selectAllByBatch(@Param("batchId") Long batchId, @Param("scope") String scope);
}
