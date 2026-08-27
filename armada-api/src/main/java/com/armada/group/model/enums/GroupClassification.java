package com.armada.group.model.enums;

/** 租户内 canonical WhatsApp 群的唯一业务分类。 */
public enum GroupClassification {

    /** 尚无首次完整 baseline 或 baseline 后可靠新增事实。 */
    UNCLASSIFIED(0),

    /** 首个成功写入的可靠事实来自账号首次完整 baseline。 */
    HISTORICAL(1),

    /** 首个成功写入的可靠事实来自 baseline 后新增群。 */
    POST_CONTROL(2);

    private final int code;

    GroupClassification(int code) {
        this.code = code;
    }

    /** 返回稳定数据库码。 */
    public int code() {
        return code;
    }

    /**
     * 按稳定数据库码解析群分类。
     *
     * @param code 数据库码
     * @return 对应分类
     * @throws IllegalArgumentException 未知码
     */
    public static GroupClassification fromCode(Integer code) {
        if (code != null) {
            for (GroupClassification classification : values()) {
                if (classification.code == code) {
                    return classification;
                }
            }
        }
        throw new IllegalArgumentException("未知群分类: " + code);
    }
}
