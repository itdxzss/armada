package com.armada.account.model.enums;

/**
 * 账号批量生命周期操作类型。
 *
 * <p>该枚举是批量预估接口与后端编排服务共享的业务契约。它表达用户要求执行的动作，
 * 不等同于账号当前的登录状态，也不复用协议 outbox 的消息类型字符串。</p>
 */
public enum AccountBatchOperation {

    /**
     * 批量登录：Armada 对可执行账号写入协议上线命令，最终在线状态等待协议事件回填。
     */
    ONLINE,

    /**
     * 批量离线：Armada 对目标账号写入协议下线命令，最终离线状态等待协议事件回填。
     */
    OFFLINE
}
