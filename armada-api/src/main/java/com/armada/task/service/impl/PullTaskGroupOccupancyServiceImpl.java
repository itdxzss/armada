package com.armada.task.service.impl;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskStandardStatus;
import com.armada.task.service.PullTaskGroupOccupancyService;
import java.util.List;
import org.springframework.stereotype.Service;

/** 基于执行行活动状态判断群组占用，不引入额外锁表。 */
@Service
public class PullTaskGroupOccupancyServiceImpl implements PullTaskGroupOccupancyService {

    private static final List<Integer> OCCUPIED_STATUSES = List.of(
            PullTaskExecutionStatus.WAIT_START.code(),
            PullTaskExecutionStatus.EXECUTING.code(),
            PullTaskExecutionStatus.WAIT_RESOURCE.code());
    private static final List<String> FOLDER_USING_TASK_STATUSES = List.of(
            PullTaskStandardStatus.WAIT_START.name(),
            PullTaskStandardStatus.EXECUTING.name(),
            PullTaskStandardStatus.PAUSED.name(),
            PullTaskStandardStatus.WAIT_GROUP_RESOURCE.name());

    private final PullTaskGroupExecutionMapper executionMapper;

    public PullTaskGroupOccupancyServiceImpl(PullTaskGroupExecutionMapper executionMapper) {
        this.executionMapper = executionMapper;
    }

    @Override
    public void requireUnoccupied(List<Long> groupLinkIds) {
        if (executionMapper.countActiveByGroupLinkIds(groupLinkIds, OCCUPIED_STATUSES) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "所选群组正在被拉人任务使用，暂不能移动或删除");
        }
    }

    @Override
    public void requireFoldersNotInUse(List<Long> folderIds) {
        if (executionMapper.countTasksUsingFolders(folderIds, FOLDER_USING_TASK_STATUSES) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "所选群组分组正在被任务使用，暂不能删除");
        }
    }
}
