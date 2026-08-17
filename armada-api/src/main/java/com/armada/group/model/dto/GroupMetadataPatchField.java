package com.armada.group.model.dto;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 允许字段级 patch 的群资料字段白名单。
 *
 * <p>枚举即白名单：协议事件 fieldMask 里出现枚举外的字段名一律计指标后跳过，不阻塞已识别字段
 * （群变更事件直投影设计 §10）。每个枚举项在 {@code wa_group_profile} 上对应一个业务列加一对
 * {@code *_source} / {@code *_observed_at} 版本列。</p>
 *
 * <p>{@code wireName} 是协议 fieldMask 使用的 camelCase 名，与 armada 全栈 wire 口径一致；
 * 枚举名本身用于数据库来源列与 Java 内部流转，两者不可混用。</p>
 *
 * <p>头像与群人数不在此列：前者当前没有明确的变更事件口径，后者由成员事实推导而非直接观察。</p>
 */
public enum GroupMetadataPatchField {

    /** WhatsApp 群名。 */
    SUBJECT("subject"),

    /** 群描述，空串表示明确观察到空描述。 */
    DESCRIPTION("description"),

    /** 仅管理员可发言。 */
    ANNOUNCE_ONLY("announceOnly"),

    /** 仅管理员可编辑群资料。 */
    ADMIN_ONLY_EDIT_INFO("adminOnlyEditInfo"),

    /** 普通成员可添加成员。 */
    MEMBER_ADD_MODE("memberAddMode"),

    /** 开启入群审批。 */
    JOIN_APPROVAL_MODE("joinApprovalMode"),

    /** 限时消息秒数，0 表示明确关闭。 */
    EPHEMERAL_DURATION_SECONDS("ephemeralDurationSeconds");

    private static final Map<String, GroupMetadataPatchField> BY_WIRE_NAME =
            Stream.of(values()).collect(Collectors.toMap(
                    field -> field.wireName.toLowerCase(Locale.ROOT), Function.identity()));

    private final String wireName;

    GroupMetadataPatchField(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 返回协议 fieldMask 使用的 camelCase 字段名。
     *
     * @return wire 字段名
     */
    public String wireName() {
        return wireName;
    }

    /**
     * 按协议 fieldMask 名解析字段。
     *
     * <p>大小写不敏感以容忍两端书写差异；未识别的名字返回空，由调用方计指标后跳过而不是抛错，
     * 避免一个未知字段阻塞同一事件里已识别的字段。</p>
     *
     * @param wireName 协议给出的字段名
     * @return 对应字段，未识别时为空
     */
    public static Optional<GroupMetadataPatchField> fromWire(String wireName) {
        if (wireName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_WIRE_NAME.get(wireName.trim().toLowerCase(Locale.ROOT)));
    }
}
