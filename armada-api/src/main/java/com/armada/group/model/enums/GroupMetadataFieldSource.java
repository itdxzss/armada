package com.armada.group.model.enums;

/**
 * 群资料字段事实的来源，决定同一事实时间下的决胜优先级。
 *
 * <p>枚举名直接作为 {@code wa_group_profile.*_source} 的列值，并在 upsert SQL 的 CASE
 * 分级表达式里按名称匹配，因此重命名会破坏存量行的比较结果。分级口径来自群变更事件直投影
 * 设计 §7.2：同一 {@code occurredAt} 时精确事件优先于完整快照。</p>
 *
 * <p>{@link #rank()} 只供 Java 侧断言与日志使用；实际决胜发生在 SQL 内，避免读-改-写引入行锁。
 * 两处分级必须保持一致。</p>
 */
public enum GroupMetadataFieldSource {

    /** 精确群资料变更事件：Web {@code groups.update} 或 Android WGP2 资料节点直投的字段级 patch。 */
    METADATA_EVENT(3),

    /** 逐群完整资料：首次建档、人工刷新或异常修复读回的单群 metadata。 */
    PROFILE_SNAPSHOT(2),

    /** 账号级群列表快照：一次上报账号全部群，每群只带少量字段。 */
    GROUP_SNAPSHOT(1);

    private final int rank;

    GroupMetadataFieldSource(int rank) {
        this.rank = rank;
    }

    /**
     * 返回该来源的可信度分级，数值越大越可信。
     *
     * @return 可信度分级
     */
    public int rank() {
        return rank;
    }
}
