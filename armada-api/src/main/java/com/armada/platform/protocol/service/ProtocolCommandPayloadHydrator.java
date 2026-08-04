package com.armada.platform.protocol.service;

import com.armada.platform.protocol.model.entity.ProtocolCommandOutbox;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 把 Outbox 中的轻量业务引用补全为协议层可执行 payload 的扩展边界。
 *
 * <p>接口定义在 platform 域，业务域实现它并读取自己的冻结事实，避免 platform 反向依赖业务
 * Mapper。实现不得把补全后的敏感 payload 回写 Outbox 或记录日志。</p>
 */
public interface ProtocolCommandPayloadHydrator {

    /**
     * 判断当前实现是否负责指定 Outbox 行。
     *
     * @param row 待发布 Outbox 行
     * @return 当前实现负责时返回 true
     */
    boolean supports(ProtocolCommandOutbox row);

    /**
     * 从业务引用和冻结事实生成 Kafka wire payload。
     *
     * @param row 待发布 Outbox 行
     * @param referencePayload Outbox 中持久化的轻量引用
     * @return 协议层可执行 payload
     */
    JsonNode hydrate(ProtocolCommandOutbox row, JsonNode referencePayload);
}
