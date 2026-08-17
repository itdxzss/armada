package com.armada.group.model.dto;

/**
 * 允许字段级 patch 的群资料字段白名单。
 *
 * <p>枚举即白名单：协议事件 fieldMask 里出现枚举外的字段名一律计指标后跳过，不阻塞已识别字段
 * （群变更事件直投影设计 §10）。每个枚举项在 {@code wa_group_profile} 上对应一个业务列加一对
 * {@code *_source} / {@code *_observed_at} 版本列。</p>
 *
 * <p>头像与群人数不在此列：前者当前没有明确的变更事件口径，后者由成员事实推导而非直接观察。</p>
 */
public enum GroupMetadataPatchField {

    /** WhatsApp 群名。 */
    SUBJECT,

    /** 群描述，空串表示明确观察到空描述。 */
    DESCRIPTION,

    /** 仅管理员可发言。 */
    ANNOUNCE_ONLY,

    /** 仅管理员可编辑群资料。 */
    ADMIN_ONLY_EDIT_INFO,

    /** 普通成员可添加成员。 */
    MEMBER_ADD_MODE,

    /** 开启入群审批。 */
    JOIN_APPROVAL_MODE,

    /** 限时消息秒数，0 表示明确关闭。 */
    EPHEMERAL_DURATION_SECONDS
}
