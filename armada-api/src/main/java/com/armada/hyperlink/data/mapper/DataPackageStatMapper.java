package com.armada.hyperlink.data.mapper;

import com.armada.hyperlink.data.model.entity.DataPackageStat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 数据包当前代统计读模型的数据访问。 */
@Mapper
public interface DataPackageStatMapper {

    /** 创建新包的一对一零值统计行。 */
    int insertInitial(DataPackageStat stat);

    /** 追加导入时原子增加当前代 unused 计数。 */
    int incrementUnused(@Param("dataPackageId") Long dataPackageId,
                        @Param("generation") int generation,
                        @Param("increment") int increment,
                        @Param("updatedAt") long updatedAt);

    /** 覆盖切代时把统计整体重置到新代。 */
    int resetGeneration(@Param("dataPackageId") Long dataPackageId,
                        @Param("expectedGeneration") int expectedGeneration,
                        @Param("newGeneration") int newGeneration,
                        @Param("unusedCount") int unusedCount,
                        @Param("updatedAt") long updatedAt);

    /** 按实际恢复行数把可重试失败计数移动到未使用。 */
    int moveRetryableFailedToUnused(@Param("dataPackageId") Long dataPackageId,
                                    @Param("generation") int generation,
                                    @Param("affected") int affected,
                                    @Param("updatedAt") long updatedAt);

    /** 内部 reconciliation 用当前代真实聚合覆盖全部互斥计数。 */
    int replaceCounts(DataPackageStat stat);
}
