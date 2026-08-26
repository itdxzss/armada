package com.armada.group.model.enums;

/** canonical 群首次分类证据来源。 */
public enum GroupClassificationSource {

    /** 账号首次完整 baseline。 */
    BASELINE_CAPTURED(1),

    /** baseline 后可靠 self-add 或完整快照新增。 */
    POST_CONTROL_DISCOVERED(2),

    /** 旧数据迁移时可比较时间的可靠事实。 */
    MIGRATION_EVIDENCE(3),

    /** 旧数据迁移时无可比较时间，按业务兜底规则收敛。 */
    MIGRATION_LEGACY_FALLBACK(4);

    private final int code;

    GroupClassificationSource(int code) {
        this.code = code;
    }

    /** 返回稳定数据库码。 */
    public int code() {
        return code;
    }

    /**
     * 按稳定数据库码解析首次分类来源。
     *
     * @param code 数据库码
     * @return 对应来源
     * @throws IllegalArgumentException 未知码
     */
    public static GroupClassificationSource fromCode(Integer code) {
        if (code != null) {
            for (GroupClassificationSource source : values()) {
                if (source.code == code) {
                    return source;
                }
            }
        }
        throw new IllegalArgumentException("未知群分类来源: " + code);
    }
}
