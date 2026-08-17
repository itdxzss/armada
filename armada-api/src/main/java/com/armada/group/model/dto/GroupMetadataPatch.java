package com.armada.group.model.dto;

import com.armada.group.model.enums.GroupMetadataFieldSource;
import java.util.Set;

/**
 * 单个群的字段级资料 patch。
 *
 * <p>只有出现在 {@link #fieldMask()} 中的字段参与写入：未进 mask 的字段一律不覆盖数据库，
 * 也不推进该字段的版本水位。进了 mask 的字段即使值为 {@code false}、{@code 0} 或空描述也必须落库，
 * 这是"未出现、明确 false、明确清空"三种语义的关键区分（群变更事件直投影设计 §1）。</p>
 *
 * <p>版本决胜按 {@link #observedAt()} 优先、同时间时按 {@link #source()} 分级，在 upsert SQL
 * 内完成。逐字段 event_id 不落列，因此同事实时间且同来源时退化为先到先赢；{@link #eventId()}
 * 仅用于整行级追溯与日志。</p>
 */
public record GroupMetadataPatch(
        /** 租户 ID。 */
        Long tenantId,
        /** WhatsApp 群 JID；群尚未建档时按它创建最小群身份。 */
        String groupJid,
        /** 本次观察到的字段名集合，取值见 {@link GroupMetadataPatchField}。 */
        Set<GroupMetadataPatchField> fieldMask,
        /** WhatsApp 群名。 */
        String subject,
        /** 群描述；进 mask 且为空串表示明确观察到空描述。 */
        String description,
        /** 是否仅管理员可发言。 */
        Boolean announceOnly,
        /** 是否仅管理员可编辑群资料。 */
        Boolean adminOnlyEditInfo,
        /** 普通成员是否可添加成员。 */
        Boolean memberAddMode,
        /** 是否开启入群审批。 */
        Boolean joinApprovalMode,
        /** 限时消息秒数，0 表示明确关闭。 */
        Integer ephemeralDurationSeconds,
        /** 事实来源，决定同一事实时间下的决胜优先级。 */
        GroupMetadataFieldSource source,
        /** 事实发生时间（epoch 毫秒），取协议事件的 occurredAt，不得用消费时间伪造。 */
        long observedAt,
        /** 协议事件 ID，仅用于整行追溯与日志。 */
        String eventId
) {

    /**
     * 判断某字段本次是否被观察到。
     *
     * @param field 字段
     * @return 该字段是否进入本次 mask
     */
    public boolean observed(GroupMetadataPatchField field) {
        return fieldMask != null && fieldMask.contains(field);
    }
}
