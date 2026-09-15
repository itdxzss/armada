package com.armada.resource.mapper;

import com.armada.resource.model.entity.GroupDataPackageImport;
import com.armada.resource.model.dto.GroupDataPackageQuery;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 拉群数据包真实数据库访问。 */
@Mapper
public interface GroupDataPackageImportMapper {
    /** 记录开始导入 */
    int insert(GroupDataPackageImport row);
    /** 完成或者失败结果 */
    int finish(GroupDataPackageImport row);
    /** 历史导入数 */
    long count(@Param("id") long id);
    /** 历史导入分页 */
    List<GroupDataPackageImport> page(@Param("id") long id, @Param("query") GroupDataPackageQuery query);
}
