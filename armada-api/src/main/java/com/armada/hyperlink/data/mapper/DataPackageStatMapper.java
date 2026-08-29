package com.armada.hyperlink.data.mapper;

import com.armada.hyperlink.data.model.entity.DataPackageStat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 数据包当前代统计读模型的数据访问。 */
@Mapper
public interface DataPackageStatMapper {

    /** 领取/释放前锁定当前代统计行，固定锁顺序。 */
    DataPackageStat selectForUpdate(@Param("dataPackageId") Long dataPackageId,
                                    @Param("generation") int generation);

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

    /** recipient 领取事务按实际影响数把未使用移动到已领取。 */
    int moveUnusedToClaimed(@Param("dataPackageId") Long dataPackageId,
                            @Param("generation") int generation,
                            @Param("affected") int affected,
                            @Param("updatedAt") long updatedAt);

    /** STOP 释放事务按实际影响数把已领取移动回未使用。 */
    int moveClaimedToUnused(@Param("dataPackageId") Long dataPackageId,
                            @Param("generation") int generation,
                            @Param("affected") int affected,
                            @Param("updatedAt") long updatedAt);

    /** 按号码真实旧/新池状态原子搬运一个当前代统计计数。 */
    int moveDeliveryStatus(@Param("dataPackageId") long dataPackageId,
            @Param("generation") int generation, @Param("fromStatus") int fromStatus,
            @Param("toStatus") int toStatus, @Param("claimedStatus") int claimedStatus,
            @Param("sentStatus") int sentStatus, @Param("deliveredStatus") int deliveredStatus,
            @Param("retryableFailedStatus") int retryableFailedStatus,
            @Param("unregisteredStatus") int unregisteredStatus,
            @Param("updatedAt") long updatedAt);

    /** 内部 reconciliation 用当前代真实聚合覆盖全部互斥计数。 */
    int replaceCounts(DataPackageStat stat);
}
