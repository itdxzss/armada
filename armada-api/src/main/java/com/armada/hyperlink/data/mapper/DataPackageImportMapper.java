package com.armada.hyperlink.data.mapper;

import com.armada.hyperlink.data.model.entity.DataPackageImport;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 数据包导入审计的数据访问。 */
@Mapper
public interface DataPackageImportMapper {

    /** 在独立短事务创建 PROCESSING 审计。 */
    int insert(DataPackageImport entity);

    /** 包行锁获取后为仍在处理中的审计确定目标代次。 */
    int assignGeneration(@Param("id") Long id,
                         @Param("processingStatus") int processingStatus,
                         @Param("generation") int generation);

    /** 业务事务成功前把审计提交为 SUCCESS。 */
    int markSuccess(@Param("id") Long id,
                    @Param("processingStatus") int processingStatus,
                    @Param("successStatus") int successStatus,
                    @Param("totalRows") int totalRows,
                    @Param("acceptedRows") int acceptedRows,
                    @Param("invalidRows") int invalidRows,
                    @Param("duplicatedRows") int duplicatedRows,
                    @Param("finishedAt") long finishedAt);

    /** 业务事务回滚后在独立事务保存脱敏失败摘要。 */
    int markFailed(@Param("id") Long id,
                   @Param("processingStatus") int processingStatus,
                   @Param("failedStatus") int failedStatus,
                   @Param("failureReason") String failureReason,
                   @Param("finishedAt") long finishedAt);

    /** 跨租户把超时 PROCESSING 审计分批收敛为 FAILED。 */
    @InterceptorIgnore(tenantLine = "true")
    int markStaleProcessingFailed(@Param("processingStatus") int processingStatus,
                                  @Param("failedStatus") int failedStatus,
                                  @Param("createdBefore") long createdBefore,
                                  @Param("failureReason") String failureReason,
                                  @Param("finishedAt") long finishedAt,
                                  @Param("limit") int limit);
}
