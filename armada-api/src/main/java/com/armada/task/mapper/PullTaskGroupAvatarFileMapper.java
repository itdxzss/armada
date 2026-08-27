package com.armada.task.mapper;

import com.armada.shared.security.DataScope;
import com.armada.task.model.entity.PullTaskGroupAvatarFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 拉群任务群头像文件归属元数据访问。 */
@Mapper
public interface PullTaskGroupAvatarFileMapper {

    /** 新增当前租户的头像归属元数据并回填主键。 */
    int insert(PullTaskGroupAvatarFile row);

    /** 用户请求按安全文件 key 查询元数据，缺失或 SYSTEM 范围时不返回数据。 */
    PullTaskGroupAvatarFile selectByFileKeyForScope(
            @Param("fileKey") String fileKey,
            @Param("scope") DataScope scope);

    /** 删除当前租户指定文件 key 的归属元数据，供已授权删除和内部清理使用。 */
    int deleteByFileKey(@Param("fileKey") String fileKey);
}
