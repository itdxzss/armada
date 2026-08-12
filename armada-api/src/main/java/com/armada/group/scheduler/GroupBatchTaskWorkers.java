package com.armada.group.scheduler;

import com.armada.group.service.impl.GroupBatchInfoRefreshWorker;
import com.armada.group.service.impl.GroupBatchLinkRefreshWorker;
import org.springframework.stereotype.Component;

/**
 * 批量任务两类执行器的组合。
 *
 * @param link 刷新群链接执行器
 * @param info 获取最新群信息执行器
 */
@Component
public record GroupBatchTaskWorkers(
        GroupBatchLinkRefreshWorker link,
        GroupBatchInfoRefreshWorker info) {
}
