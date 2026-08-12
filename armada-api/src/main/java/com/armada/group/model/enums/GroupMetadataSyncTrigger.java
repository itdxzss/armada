package com.armada.group.model.enums;

/** 群详情同步任务触发来源。 */
public enum GroupMetadataSyncTrigger {
    BASELINE_CAPTURED(1),
    POST_CONTROL_DISCOVERED(2),
    PARTICIPANT_CHANGED(3),
    METADATA_CHANGED(4),
    ACCOUNT_ONLINE(5),
    MANUAL_REFRESH(6),
    BACKFILL(7);

    private final int code;

    GroupMetadataSyncTrigger(int code) {
        this.code = code;
    }

    /** 返回稳定数据库码。 */
    public int code() {
        return code;
    }

    /**
     * 按稳定数据库码解析触发来源。
     *
     * @param code 数据库码
     * @return 对应触发来源
     * @throws IllegalArgumentException 未知码
     */
    public static GroupMetadataSyncTrigger fromCode(Integer code) {
        if (code != null) {
            for (GroupMetadataSyncTrigger trigger : values()) {
                if (trigger.code == code) {
                    return trigger;
                }
            }
        }
        throw new IllegalArgumentException("未知群详情同步触发来源: " + code);
    }
}
