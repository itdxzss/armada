package com.armada.platform.protocol.model.result;

import java.time.Instant;

/**
 * 协议层账号主动探活结果。
 *
 * <p>对应协议层 {@code POST /v1/accounts/{id}/probe} 响应。probe 会真实触达 WhatsApp,
 * 因此只用于人工诊断或关键操作前确认,不作为高频热路径默认前置。</p>
 *
 * @param ok         是否探活成功
 * @param probedAt   探活发起/完成时间
 * @param latencyMs  端到端耗时
 * @param reasonCode 失败原因;成功通常为 OK
 */
public record ProtocolProbeResult(
        boolean ok,
        Instant probedAt,
        Long latencyMs,
        String reasonCode
) {
}
