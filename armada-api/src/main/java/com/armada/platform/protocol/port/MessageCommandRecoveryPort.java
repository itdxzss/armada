package com.armada.platform.protocol.port;

/** 复用已落库消息命令做协议结果恢复，不生成第二个 commandId。 */
public interface MessageCommandRecoveryPort {

    /**
     * 把已投递或死信的原消息命令重新放回 outbox 扫描队列。
     *
     * <p>协议执行端必须以 commandId 持久幂等；调用方只允许在对应幂等 tombstone
     * 的保留窗口内调用。该动作不会插入新 outbox 行。</p>
     *
     * @param tenantId 命令所属租户
     * @param commandId 原稳定命令 ID
     * @param now 恢复时间(epoch 毫秒)
     * @return true 表示原命令已从 SENT/DEAD 重新排队；false 表示不在可重排状态
     */
    boolean replay(long tenantId, String commandId, long now);
}
