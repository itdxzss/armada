package com.armada.group.scheduler;

import java.util.concurrent.Executor;

/**
 * 批量任务两层执行器的组合。
 *
 * @param task 任务层执行器:只扫描与分派
 * @param item 明细层执行器:实时直调协议
 */
public record GroupBatchTaskExecutors(Executor task, Executor item) {
}
