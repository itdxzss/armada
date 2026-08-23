package com.armada.task.service;

import java.util.List;

/** 向群组域提供拉人任务占用与资源池引用校验。 */
public interface PullTaskGroupOccupancyService {

    /** 群组被活动执行行占用时拒绝人工移动或删除。 */
    void requireUnoccupied(List<Long> groupLinkIds);

    /** 分组被未结束任务引用时拒绝删除。 */
    void requireFoldersNotInUse(List<Long> folderIds);
}
