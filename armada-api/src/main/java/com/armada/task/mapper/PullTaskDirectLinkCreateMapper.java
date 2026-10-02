package com.armada.task.mapper;

import com.armada.task.model.entity.PullTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 直接链接正式建单与请求幂等查找；租户由统一拦截器隔离。 */
@Mapper
public interface PullTaskDirectLinkCreateMapper {
    /** 按可信创建人和客户端请求标识读取既有正式任务，包括已软删任务防止重建。 */
    PullTask selectByRequest(@Param("userId") long userId, @Param("requestId") String requestId);

    /** 一次插入正式待启动任务并回填真实 ID；唯一索引原子阻止重复建单。 */
    int insert(@Param("task") PullTask task, @Param("requestId") String requestId);
}
