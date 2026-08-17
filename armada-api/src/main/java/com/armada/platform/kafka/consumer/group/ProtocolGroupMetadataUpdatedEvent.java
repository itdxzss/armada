package com.armada.platform.kafka.consumer.group;

import java.util.List;

/**
 * 协议群资料字段级变更 Kafka 事件。
 *
 * <p>顶层 {@code accountId} 对应 Armada 的 {@code protocol_account_id}，其余字段来自
 * envelope.data。{@code fieldMask} 是本次实际观察到的字段名列表（协议 camelCase 口径），
 * 只有出现在其中的字段才允许写库；未进 mask 的同名值一律忽略，即使 payload 里带了值。</p>
 *
 * <p>字段值一律可空，且可空不等于未观察：进了 mask 的 {@code null} 描述表示明确清空，
 * {@code false} 与 {@code 0} 同理必须落库。区分"未出现 / 明确 false / 明确清空"三种语义是本
 * 事件的核心约束（群变更事件直投影设计 §1、§6.2）。</p>
 *
 * @param eventId                  协议层事件 ID
 * @param tenantId                 租户 ID
 * @param accountId                Armada 本地账号 ID
 * @param protocolAccountId        协议账号句柄
 * @param protocolBackend          协议后端，WEB 或 ANDROID
 * @param groupJid                 WhatsApp 群 JID
 * @param fieldMask                本次观察到的字段名（协议 wire 名，未去重前原样保留）
 * @param subject                  群名
 * @param description              群描述
 * @param announceOnly             是否仅管理员发言
 * @param adminOnlyEditInfo        是否仅管理员可编辑群资料
 * @param memberAddMode            普通成员是否可添加成员
 * @param joinApprovalMode         是否开启入群审批
 * @param ephemeralDurationSeconds 限时消息秒数，0 表示明确关闭
 * @param author                   变更操作人，仅用于追溯
 * @param source                   协议侧来源标识，仅用于追溯
 * @param occurredAt               事实发生时间(epoch 毫秒)
 * @param workerId                 产生事件的协议层 worker ID
 */
public record ProtocolGroupMetadataUpdatedEvent(
        String eventId,
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String protocolBackend,
        String groupJid,
        List<String> fieldMask,
        String subject,
        String description,
        Boolean announceOnly,
        Boolean adminOnlyEditInfo,
        Boolean memberAddMode,
        Boolean joinApprovalMode,
        Integer ephemeralDurationSeconds,
        String author,
        String source,
        long occurredAt,
        String workerId
) {
}
