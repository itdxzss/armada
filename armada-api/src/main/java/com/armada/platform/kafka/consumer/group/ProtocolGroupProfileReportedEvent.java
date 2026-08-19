package com.armada.platform.kafka.consumer.group;

import java.util.List;

/**
 * 单个群的完整资料上报事件。
 *
 * <p>协议侧首次建档、人工刷新或异常修复读回完整 metadata 后，按群逐条上报：一条消息只承载一个群，
 * 避免整账号快照撞 Kafka 单消息上限，也让控端能按群小事务落库、部分失败互不影响
 * （群变更事件直投影设计 §5.2、§9）。</p>
 *
 * <p>资料字段与 {@code group.metadata_updated} 共用同一套字段级 reducer，区别只在来源可信度：
 * 完整快照低于精确变更事件，因此同一事实时间下不会压过后者。</p>
 *
 * <p>{@code membersComplete} 是退群判定的授权开关：只有协议明确保证成员列表是该群全集时才为
 * {@code true}，控端据此把"库里有而列表里没有"的成员判为已退群。列表可能被截断或部分失败时
 * 必须为 {@code false}，否则会误判一批成员退群。</p>
 *
 * @param eventId          协议层事件 ID
 * @param tenantId         租户 ID
 * @param accountId        Armada 本地账号 ID
 * @param protocolAccountId 协议账号句柄
 * @param protocolBackend  协议后端，WEB 或 ANDROID
 * @param groupJid         WhatsApp 群 JID
 * @param fieldMask        本次观察到的资料字段名（协议 wire 名）
 * @param subject          群名
 * @param description      群描述
 * @param announceOnly     是否仅管理员发言
 * @param adminOnlyEditInfo 是否仅管理员可编辑群资料
 * @param memberAddMode    普通成员是否可添加成员
 * @param joinApprovalMode 是否开启入群审批
 * @param ephemeralDurationSeconds 限时消息秒数，0 表示明确关闭
 * @param members          群成员列表；为空表示本次未观察成员
 * @param membersComplete  成员列表是否为该群全集，决定能否判定退群
 * @param source           协议侧来源标识，仅用于追溯
 * @param occurredAt       事实发生时间(epoch 毫秒)
 * @param groupCreatedAt   WhatsApp 建群时间(epoch 毫秒)，协议侧已换算；未观察为 null
 * @param creatorPhone     建群人手机号(裸号)，用于创建者展示与国旗推导；未观察为 null
 * @param workerId         产生事件的协议层 worker ID
 */
public record ProtocolGroupProfileReportedEvent(
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
        List<Member> members,
        boolean membersComplete,
        String source,
        long occurredAt,
        Long groupCreatedAt,
        String creatorPhone,
        String workerId,
        String commandId
) {

    /**
     * 群成员的一条身份与角色事实。
     *
     * <p>身份三选一即可，但至少要有一个：WhatsApp 群成员列表已逐步只返回 LID，号码由协议侧从
     * {@code phone_number} 属性或 LID 映射还原后带上来。控端不猜号码——{@code phone} 缺失时
     * 仍保存成员事实，只是无法关联受控账号，等身份补齐后再关联（设计 §10）。</p>
     *
     * @param jid   成员主标识，可能是 PN 或 LID 形式
     * @param lid   LID 形式身份，可空
     * @param phone 手机号，可空
     * @param admin 是否管理员
     * @param owner 是否群主
     * @param role  协议原始角色串，仅用于追溯
     */
    public record Member(
            String jid,
            String lid,
            String phone,
            Boolean admin,
            Boolean owner,
            String role
    ) {
    }
}
