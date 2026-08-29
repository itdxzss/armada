package com.armada.hyperlink.data.mapper;

import com.armada.hyperlink.data.model.dto.DataPackagePhoneQuery;
import com.armada.hyperlink.data.model.entity.DataPackagePhone;
import com.armada.hyperlink.data.model.vo.DataPackagePhoneCleanupRow;
import com.armada.hyperlink.data.model.vo.DataPackageStatusCountRow;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 数据包号码成员的批量写入、当前代分页与保留期清理。 */
@Mapper
public interface DataPackagePhoneMapper {

    /** 批量插入同一导入批次产生的号码成员。 */
    int batchInsert(@Param("rows") List<DataPackagePhone> rows);

    /** 查询候选中已存在于指定包代次的号码。 */
    List<String> selectExistingPhones(@Param("dataPackageId") Long dataPackageId,
                                      @Param("generation") int generation,
                                      @Param("phones") List<String> phones);

    /** 统计父包当前代、当前筛选条件下的号码数。 */
    long countPage(@Param("q") DataPackagePhoneQuery query);

    /** 查询父包当前代号码明细页。 */
    List<DataPackagePhone> selectPage(@Param("q") DataPackagePhoneQuery query);

    /** 把父包当前代可重试失败号码恢复为未使用。 */
    int resetPoolStatus(@Param("dataPackageId") Long dataPackageId,
                        @Param("generation") int generation,
                        @Param("fromStatus") int fromStatus,
                        @Param("toStatus") int toStatus,
                        @Param("updatedAt") long updatedAt);

    /** 按一个或多个池状态导出父包当前代号码；空状态集合表示全部。 */
    List<String> selectPhonesForExport(@Param("dataPackageId") Long dataPackageId,
                                       @Param("generation") int generation,
                                       @Param("poolStatusCodes") List<Integer> poolStatusCodes);

    /** 统计同一导出口径的号码数，用于在读取手机号前限制响应内存。 */
    long countPhonesForExport(@Param("dataPackageId") Long dataPackageId,
                              @Param("generation") int generation,
                              @Param("poolStatusCodes") List<Integer> poolStatusCodes);

    /** 内部校准按互斥池状态聚合当前代号码。 */
    List<DataPackageStatusCountRow> selectStatusCounts(
            @Param("dataPackageId") Long dataPackageId,
            @Param("generation") int generation);

    /**
     * 跨租户扫描退役满保留期或软删包满保留期的号码行。
     * SQL 自身显式关联相同 tenant_id，只返回清理所需 tenant/id。
     */
    @InterceptorIgnore(tenantLine = "true")
    List<DataPackagePhoneCleanupRow> selectCleanupCandidates(
            @Param("retiredBefore") long retiredBefore,
            @Param("deletedBefore") long deletedBefore,
            @Param("overwriteMode") int overwriteMode,
            @Param("successStatus") int successStatus,
            @Param("limit") int limit);

    /** 按扫描得到的显式 tenant/id 对硬删一批号码行。 */
    @InterceptorIgnore(tenantLine = "true")
    int deleteCleanupCandidates(@Param("rows") List<DataPackagePhoneCleanupRow> rows);
}
