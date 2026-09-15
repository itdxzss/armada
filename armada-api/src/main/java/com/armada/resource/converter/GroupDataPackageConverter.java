package com.armada.resource.converter;

import com.armada.resource.model.entity.GroupDataPackage;
import com.armada.resource.model.entity.GroupDataPackageImport;
import com.armada.resource.model.entity.GroupDataPackagePhone;
import com.armada.resource.model.entity.GroupDataPackageStat;
import com.armada.resource.model.vo.GroupDataPackageVO;
import com.armada.resource.model.vo.GroupDataPackageMetricsVO;
import com.armada.resource.model.vo.GroupDataPackagePhoneVO;
import com.armada.resource.model.vo.GroupDataPackageImportVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** 资源实体到接口的显式转换。 */
@Mapper(componentModel = "spring")
public interface GroupDataPackageConverter {
    /** 组合包信息及当前代读模型。 */
    @Mapping(target="id", source="row.id")
    @Mapping(target="generation", source="row.generation")
    @Mapping(target="primaryCountryIso2", source="stat.primaryCountryIso2")
    @Mapping(target="continent", source="stat.continent")
    @Mapping(target="metrics", source="stat")
    @Mapping(target="usageBusinesses", expression="java(row.getLastUsedAt() == null ? java.util.List.of() : java.util.List.of(\"STANDARD_PULL\"))")
    GroupDataPackageVO detail(GroupDataPackage row, GroupDataPackageStat stat);
    /** 统计值已由数据库维护，不在接口猜测结果。 */
    GroupDataPackageMetricsVO metrics(GroupDataPackageStat row);
    /** 号码状态以稳定枚举名输出。 */
    @Mapping(target="status", expression="java(com.armada.resource.model.enums.GroupDataPackagePhoneStatus.of(row.getStatus()).name())")
    GroupDataPackagePhoneVO phone(GroupDataPackagePhone row);
    /** 导入审计接口。 */
    @Mapping(target="mode", qualifiedByName="modeName")
    GroupDataPackageImportVO imported(GroupDataPackageImport row);
    /** 数据库导入模式对应HTTP合同。 */
    @org.mapstruct.Named("modeName")
    default String modeName(Integer mode) { return mode == 1 ? "append" : "overwrite"; }
}
