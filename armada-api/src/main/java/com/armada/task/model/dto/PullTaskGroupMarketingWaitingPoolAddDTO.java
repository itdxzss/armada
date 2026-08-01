package com.armada.task.model.dto;

import java.util.List;

/**
 * 加入拉群营销等待池请求。
 *
 * @param reservationToken 已有等待池随机标识；首次加入时为空，由服务端生成
 * @param taskName          当前表单任务名称快照
 * @param plannedStartAt    计划启动时间(epoch毫秒)；立即启动时为空
 * @param groupJids         待软占用的群 JID
 */
public record PullTaskGroupMarketingWaitingPoolAddDTO(
        String reservationToken,
        String taskName,
        Long plannedStartAt,
        List<String> groupJids) {
}
