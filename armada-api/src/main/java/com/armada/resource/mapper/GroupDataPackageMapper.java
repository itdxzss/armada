package com.armada.resource.mapper;

import com.armada.resource.model.entity.GroupDataPackage;
import com.armada.resource.model.dto.GroupDataPackageQuery;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 拉群数据包真实数据库访问。 */
@Mapper
public interface GroupDataPackageMapper {
    /** 创建空包 */
    int insert(GroupDataPackage row);
    /** 读取未删包 */
    GroupDataPackage selectActive(@Param("id") long id);
    /** 锁定包头以串行化资源写入 */
    GroupDataPackage lockActive(@Param("id") long id);
    /** 列表总数 */
    long count(@Param("query") GroupDataPackageQuery query);
    /** 数据库分页 */
    List<GroupDataPackage> page(@Param("query") GroupDataPackageQuery query);
    /** 更新名称备注 */
    int updateMetadata(GroupDataPackage row);
    /** 覆盖切换代次 */
    int switchGeneration(@Param("id") long id, @Param("generation") int generation, @Param("now") long now);
    /** 记录实际任务使用 */
    int markUsed(@Param("id") long id, @Param("now") long now);
    /** 软删除 */
    int delete(@Param("id") long id, @Param("now") long now);
}
