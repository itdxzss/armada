package com.armada.group.mapper;

import com.armada.group.model.dto.GroupLinkImportDetailQuery;
import com.armada.group.model.entity.GroupLinkImportDetail;
import com.armada.group.model.vo.GroupLinkImportDetailVoRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 群链接导入明细数据访问。tenant_id 由租户行隔离拦截器自动注入,SQL 不手写 tenant_id 过滤。
 */
@Mapper
public interface GroupLinkImportDetailMapper {

    /**
     * 批量插入导入明细。
     *
     * @param rows 明细实体列表
     * @return 影响行数
     */
    int batchInsert(@Param("rows") List<GroupLinkImportDetail> rows);

    /**
     * 按查询条件计总数(JOIN batch 取 label_id,口径与 selectPage 一致)。
     *
     * @param query 查询参数(含 labelId/batchId/result/failReason)
     * @return 总数
     */
    long countByQuery(GroupLinkImportDetailQuery query);

    /**
     * 按查询条件分页列表(JOIN batch 取 sourceFileName)。
     *
     * @param query 查询参数(含 labelId/batchId/result/failReason/page/pageSize)
     * @return 投影行列表
     */
    List<GroupLinkImportDetailVoRow> selectPage(GroupLinkImportDetailQuery query);

    /**
     * 查询失败明细(result=2),用于导出失败数据,重复/格式错误由 failReason 区分。
     *
     * @param labelId 分组 ID(可选)
     * @param batchId 批次 ID(可选)
     * @return 失败明细投影列表
     */
    List<GroupLinkImportDetailVoRow> selectFailed(@Param("labelId") Long labelId,
                                                   @Param("batchId") Long batchId);
}
