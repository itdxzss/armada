package com.armada.platform.kafka.consumer.account;

/**
 * 协议账号自身群关系精确变更事件的下游处理端口。
 *
 * <p>consumer 只依赖该 platform 端口完成事件分发，具体的 group 域校验和状态写入由适配实现负责，
 * 避免 Kafka 接入层直接依赖业务域内部模型。</p>
 */
public interface ProtocolAccountGroupMembershipChangedSink {

    /**
     * 处理已经完成 envelope 解析和路由账号一致性校验的精确关系事件。
     *
     * <p>实现抛出的异常不在 consumer 内吞掉，由 Kafka listener 按容器策略重试。</p>
     *
     * @param event 协议账号自身群关系精确变更事件
     * @throws RuntimeException 当业务校验或状态写入失败时抛出
     */
    void handleMembershipChanged(ProtocolAccountGroupMembershipChangedEvent event);
}
