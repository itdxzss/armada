package com.armada.task.model.dto;

/**
 * 执行行调度租约快照。
 *
 * @param lockOwner      持有租约的调度实例
 * @param expectedVersion 本次业务回写使用的乐观锁版本
 */
public record PullTaskExecutionLease(String lockOwner, int expectedVersion) {
}
