package com.armada.task.service;

import com.armada.shared.response.PageResult;
import com.armada.task.model.dto.PullTaskQuery;
import com.armada.task.model.vo.PullTaskListVO;

/** 拉群任务统一列表读服务。 */
public interface PullTaskListService {

    /**
     * 查询普通拉群与拉群营销任务的公共一级列表。
     *
     * @param query 分页和筛选参数
     * @return 当前租户的九列列表页
     */
    PageResult<PullTaskListVO> list(PullTaskQuery query);
}
