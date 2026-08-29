package com.armada.hyperlink.task.port;

/** 超链任务变更与计费动作的持久审计端口。 */
public interface HyperlinkTaskAuditPort {

    /**
     * 在任何受审计副作用前确认真实审计落点可写。
     *
     * <p>实现不得用普通应用日志冒充持久审计；不可用时必须抛出异常。</p>
     */
    void requireAvailable();

    /**
     * 持久化一条审计事件。
     *
     * <p>实现必须按 {@link AuditEvent#eventId()} 幂等，任务动作应参与调用方数据库事务；
     * 计费事件使用钱包操作幂等键，允许恢复器安全重放。</p>
     *
     * @param event 不含手机号、URL、quoteToken 等敏感内容的审计事件
     */
    void record(AuditEvent event);

    /** 审计动作类型。 */
    enum Action {
        /** 创建任务。 */
        CREATE,
        /** 编辑未开始任务。 */
        UPDATE,
        /** 启动任务。 */
        START,
        /** 暂停任务。 */
        PAUSE,
        /** 继续任务。 */
        RESUME,
        /** 停止任务。 */
        STOP,
        /** 导出任务收信人流水。 */
        EXPORT_RECIPIENTS,
        /** 导出发信账号维度统计。 */
        ACCOUNT_STATS_EXPORT,
        /** 冻结任务余额。 */
        BILLING_RESERVE,
        /** 调整任务冻结额。 */
        BILLING_ADJUST,
        /** 结算任务实际发送。 */
        BILLING_SETTLE,
        /** 释放任务剩余冻结额。 */
        BILLING_RELEASE,
        /** 读取包含 IP/UA 的深度归因。 */
        ATTRIBUTION_READ,
        /** 创建深度归因导出。 */
        ATTRIBUTION_EXPORT,
        /** 创建访问趋势导出。 */
        VISIT_TREND_EXPORT
    }

    /**
     * 最小审计事实。
     *
     * @param eventId 幂等事件键
     * @param action 动作
     * @param tenantId 租户 ID
     * @param actorUserId 用户动作的操作人；后台计费恢复为 null
     * @param taskId 超链任务 ID
     * @param occurredAt 动作确认时间(epoch 毫秒)
     */
    record AuditEvent(String eventId, Action action, long tenantId, Long actorUserId,
                      long taskId, long occurredAt) { }
}
