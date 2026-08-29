package com.armada.platform.kafka.consumer.contact;

import java.util.List;

/**
 * 协议层上报的账号通讯录快照分片。
 *
 * <p>分片共享同一个 {@code snapshotId} 与 {@code snapshotCutoff}；Kafka 不保证分片顺序，
 * 因此消费方以「本快照已落库条数 == totalCount」判定收齐，而不是等最后一片。</p>
 *
 * @param eventId 协议事件 ID
 * @param tenantId 租户 ID
 * @param accountId Armada 账号 ID
 * @param protocolAccountId 协议账号句柄
 * @param snapshotId 同一逻辑快照的稳定标识
 * @param queryStartedAt 开始拉取时间（epoch 毫秒）
 * @param snapshotCutoff 快照截止时间（epoch 毫秒），落库即 synced_at
 * @param snapshotComplete 协议层是否判定本快照完整
 * @param chunkSeq 分片序号，0 起
 * @param chunkCount 分片总数
 * @param totalCount 本快照联系人总条数（跨全部分片）
 * @param contacts 本分片联系人
 */
public record AccountContactsReportedEvent(
        String eventId,
        Long tenantId,
        Long accountId,
        String protocolAccountId,
        String snapshotId,
        Long queryStartedAt,
        Long snapshotCutoff,
        boolean snapshotComplete,
        int chunkSeq,
        int chunkCount,
        int totalCount,
        List<ReportedContact> contacts
) {

    /** 组件做防御性拷贝，实例不可变。 */
    public AccountContactsReportedEvent {
        contacts = contacts == null ? List.of() : List.copyOf(contacts);
    }

    /**
     * 快照中的单个联系人。
     *
     * @param phone 不带加号的纯数字号码
     * @param jid 规范用户 JID
     * @param fullName 通讯录全名
     * @param firstName 通讯录名；Web 协议恒为 null
     * @param pushName 对方设置的展示名
     * @param businessName 商业号认证名
     */
    public record ReportedContact(
            String phone,
            String jid,
            String fullName,
            String firstName,
            String pushName,
            String businessName
    ) {
    }
}
