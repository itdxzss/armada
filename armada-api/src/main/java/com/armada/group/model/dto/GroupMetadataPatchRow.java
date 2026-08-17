package com.armada.group.model.dto;

/**
 * 群资料字段 patch 的持久化行，fieldMask 语义已在 service 层归约完毕。
 *
 * <p>归约口径：未进 mask 的字段，值 / {@code *Source} / {@code *ObservedAt} 三者一律为
 * {@code null}；进了 mask 的字段三者都非空，即使业务值本身是 {@code false}、{@code 0} 或空描述。
 * 因此 upsert SQL 只需判断 {@code *ObservedAt IS NOT NULL} 即可知道该字段本次是否被观察到，
 * 不必在 SQL 里重复表达 mask。</p>
 *
 * <p>{@code sourceRank} 是本次事件来源的可信度分级，由 {@link
 * com.armada.group.model.enums.GroupMetadataFieldSource#rank()} 提供，用于同一事实时间下与
 * 库中已存来源比较。整行水位 {@code metadata_observed_at} 不由本行推进——它只服务于账号级
 * 快照的整行判定，字段级 patch 推进它会让快照被误判为旧。</p>
 */
public record GroupMetadataPatchRow(
        /** 已解析的群主键。 */
        Long groupId,
        /** 本次事件来源的可信度分级。 */
        int sourceRank,
        /** 写入时间（epoch 毫秒）。 */
        long now,
        /** 群名，未观察时为 null。 */
        String subject,
        /** 群名来源，未观察时为 null。 */
        String subjectSource,
        /** 群名事实时间，未观察时为 null。 */
        Long subjectObservedAt,
        /** 群描述，未观察时为 null；空串表示明确观察到空描述。 */
        String description,
        /** 群描述来源。 */
        String descriptionSource,
        /** 群描述事实时间。 */
        Long descriptionObservedAt,
        /** 仅管理员发言。 */
        Boolean announceOnly,
        /** 仅管理员发言来源。 */
        String announceOnlySource,
        /** 仅管理员发言事实时间。 */
        Long announceOnlyObservedAt,
        /** 仅管理员编辑群资料。 */
        Boolean adminOnlyEditInfo,
        /** 仅管理员编辑群资料来源。 */
        String adminOnlyEditInfoSource,
        /** 仅管理员编辑群资料事实时间。 */
        Long adminOnlyEditInfoObservedAt,
        /** 普通成员可加人。 */
        Boolean memberAddMode,
        /** 普通成员可加人来源。 */
        String memberAddModeSource,
        /** 普通成员可加人事实时间。 */
        Long memberAddModeObservedAt,
        /** 入群审批。 */
        Boolean joinApprovalMode,
        /** 入群审批来源。 */
        String joinApprovalModeSource,
        /** 入群审批事实时间。 */
        Long joinApprovalModeObservedAt,
        /** 限时消息秒数，0 表示明确关闭。 */
        Integer ephemeralDurationSeconds,
        /** 限时消息来源。 */
        String ephemeralDurationSecondsSource,
        /** 限时消息事实时间。 */
        Long ephemeralDurationSecondsObservedAt
) {
}
