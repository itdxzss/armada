package com.armada.hyperlink.data.mapper;

import com.armada.hyperlink.data.model.dto.DataPackageQuery;
import com.armada.hyperlink.data.model.entity.DataPackage;
import com.armada.hyperlink.data.model.vo.DataPackageCountryRow;
import com.armada.hyperlink.data.model.vo.DataPackageListRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 数据包主数据与只读分页投影的数据访问。 */
@Mapper
public interface DataPackageMapper {

    /** 插入一个初始 generation=1 的数据包。 */
    int insert(DataPackage entity);

    /** 查询当前租户未删除数据包。 */
    DataPackage selectActiveById(@Param("id") Long id);

    /** 锁定当前租户未删除数据包，串行化导入、编辑与删除。 */
    DataPackage selectActiveForUpdate(@Param("id") Long id);

    /** 按列表条件统计数据包总数。 */
    long countPage(@Param("q") DataPackageQuery query);

    /** 只连接统计读模型查询当前页，不对号码表做分页级聚合。 */
    List<DataPackageListRow> selectPage(@Param("q") DataPackageQuery query);

    /** 查询一个数据包与统计读模型的一对一详情投影。 */
    DataPackageListRow selectSummaryById(@Param("id") Long id);

    /** 批量查询当前页数据包当前代的 DISTINCT 国家。 */
    List<DataPackageCountryRow> selectCurrentCountries(
            @Param("packageIds") List<Long> packageIds);

    /** 以乐观锁版本更新名称和备注。 */
    int updateMetadata(@Param("id") Long id,
                       @Param("name") String name,
                       @Param("remark") String remark,
                       @Param("version") int version,
                       @Param("updatedAt") long updatedAt);

    /** 追加成功后增加当前代号码总数，不修改元数据版本。 */
    int incrementPhoneCount(@Param("id") Long id,
                            @Param("generation") int generation,
                            @Param("increment") int increment,
                            @Param("updatedAt") long updatedAt);

    /** 覆盖导入完成写入后原子切换当前代和总数。 */
    int switchGeneration(@Param("id") Long id,
                         @Param("expectedGeneration") int expectedGeneration,
                         @Param("newGeneration") int newGeneration,
                         @Param("phoneCount") int phoneCount,
                         @Param("updatedAt") long updatedAt);

    /** 内部统计校准同步当前代总数，不修改元数据版本。 */
    int setPhoneCount(@Param("id") Long id,
                      @Param("generation") int generation,
                      @Param("phoneCount") int phoneCount,
                      @Param("updatedAt") long updatedAt);

    /** 软删除已锁定的数据包。 */
    int softDelete(@Param("id") Long id,
                   @Param("deletedBy") Long deletedBy,
                   @Param("deletedAt") long deletedAt);
}
