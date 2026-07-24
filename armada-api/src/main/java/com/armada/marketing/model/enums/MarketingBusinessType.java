package com.armada.marketing.model.enums;

/** 营销任务所属业务菜单类型。 */
public enum MarketingBusinessType {

    /** 单纯营销任务。 */
    ORDINARY(1),

    /** 拉群营销任务。 */
    GROUP_PULL(2);

    private final int code;

    MarketingBusinessType(int code) {
        this.code = code;
    }

    /** 返回数据库持久化码值。 */
    public int code() {
        return code;
    }

    /**
     * 按数据库码值解析业务类型。
     *
     * @param code 数据库码值
     * @return 对应业务类型
     * @throws IllegalArgumentException 未知码值
     */
    public static MarketingBusinessType fromCode(int code) {
        return switch (code) {
            case 1 -> ORDINARY;
            case 2 -> GROUP_PULL;
            default -> throw new IllegalArgumentException("未知营销业务类型: " + code);
        };
    }
}
