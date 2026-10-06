package com.armada.task.mapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** 草稿及首次启动前的注销开关原子写入。 */
@Mapper
public interface PullTaskCreatorDeletionConfigMapper {
    /** 父任务锁下更新，SQL 再次限制状态、租户和首次启动水位。 */
    int updateBeforeStart(@Param("taskId") long taskId, @Param("enabled") int enabled,
            @Param("now") long now);
}
