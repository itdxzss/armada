package com.armada.marketing.grouppull.model.enums;

/** 拉群营销料子当前使用状态。 */
public enum GroupPullMaterialStatus {

    /** 可被新群抽取。 */
    AVAILABLE(1),

    /** 已被一个未收口执行预留。 */
    RESERVED(2),

    /** 已进入完整建群成功的群组。 */
    USED_BY_SUCCESS_GROUP(3),

    /** 已进入最终建群失败的群组，不得再次抽取。 */
    USED_BY_FAILED_GROUP(4);

    private final int code;

    GroupPullMaterialStatus(int code) {
        this.code = code;
    }

    /** 返回数据库持久化码值。 */
    public int code() {
        return code;
    }

    /** 按数据库码值解析料子状态。 */
    public static GroupPullMaterialStatus fromCode(int code) {
        return switch (code) {
            case 1 -> AVAILABLE;
            case 2 -> RESERVED;
            case 3 -> USED_BY_SUCCESS_GROUP;
            case 4 -> USED_BY_FAILED_GROUP;
            default -> throw new IllegalArgumentException("未知拉群料子状态: " + code);
        };
    }
}
