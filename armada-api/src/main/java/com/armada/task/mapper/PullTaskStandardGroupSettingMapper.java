package com.armada.task.mapper;

import com.armada.task.model.entity.PullTaskStandardGroupSetting;
import com.armada.task.model.vo.PullTaskAvatarReference;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 普通群链接任务群资料与权限设置数据访问。 */
@Mapper
public interface PullTaskStandardGroupSettingMapper {

    /** 新增任务级群资料设置。 */
    int insert(PullTaskStandardGroupSetting row);

    /** 按任务 ID 读取当前租户的群资料设置。 */
    PullTaskStandardGroupSetting selectByTaskId(@Param("taskId") long taskId);

    /** 统计当前租户仍被有效任务绑定的头像 key。 */
    long countActiveTaskBindings(@Param("avatarFileKey") String avatarFileKey);

    /** 按当前租户待删任务 ID 捕获仍有效的头像引用。 */
    List<PullTaskAvatarReference> selectActiveAvatarReferencesByTaskIds(
            @Param("taskIds") List<Long> taskIds);
}
