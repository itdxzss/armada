package com.armada.group.service;

/** 历史群一次性拉人后台执行器。 */
public interface HistoricalGroupPullWorker {

    /**
     * 在指定租户上下文内执行一次已认领拉人任务。
     *
     * <p>执行只消费 {@code RUNNING} 状态及仍待处理的成员，不重选拉手、不重试任何协议动作。
     * 方法负责设置并恢复线程租户上下文，可安全从受控线程池调用。</p>
     *
     * @param tenantId    执行所属租户 ID
     * @param executionId 已原子认领的执行 ID
     */
    void execute(Long tenantId, Long executionId);
}
