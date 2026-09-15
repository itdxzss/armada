package com.armada.resource.mapper;

import com.armada.resource.model.entity.GroupDataPackageStat;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 拉群数据包真实数据库访问。 */
@Mapper
public interface GroupDataPackageStatMapper {
    /** 初始化空包统计 */
    int insert(@Param("id") long id);
    /** 读取包统计 */
    GroupDataPackageStat select(@Param("id") long id);
    /** 读取本页统计 */
    List<GroupDataPackageStat> selectByIds(@Param("ids") List<Long> ids);
    /** 切换代次后重置统计 */
    int reset(@Param("id") long id, @Param("generation") int generation);
    /** 同事务写入号码导入增量 */
    int addImported(@Param("id") long id, @Param("count") int count);
    /** 按一次成功状态更新维护统计 */
    int move(@Param("id") long id, @Param("generation") int generation, @Param("from") int from, @Param("to") int to, @Param("count") int count);
    /** 更新主要国家及大洲 */
    int country(@Param("id") long id, @Param("country") String country, @Param("continent") String continent);
}
