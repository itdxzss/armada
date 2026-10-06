package com.armada.account.model.enums;

/** 一次性建群账号生命周期，独立于可被协议事件更新的在线状态。 */
public enum AccountCreatorDeletionLifecycle {
    /** 已冻结给唯一建群执行行，只允许该行的建群前置操作。 */
    RESERVED,
    /** 注销意图已持久化，任何业务和重新上线均被禁止。 */
    DELETING,
    /** 注销和清理证据均已确认，永久保留审计与禁止重新派单。 */
    DELETED
}
