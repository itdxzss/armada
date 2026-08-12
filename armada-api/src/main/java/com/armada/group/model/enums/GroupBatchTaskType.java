package com.armada.group.model.enums;

/** 群组列表批量操作类型。 */
public enum GroupBatchTaskType {

    /** 批量刷新群链接：逐群用管理员账号重新拉取当前邀请链接并回填。 */
    REFRESH_LINK(1),

    /** 批量获取最新群信息：逐群排队群详情同步，成功后按字段级规则回填快照。 */
    REFRESH_INFO(2);

    private final int code;

    GroupBatchTaskType(int code) {
        this.code = code;
    }

    /** 返回稳定数据库码。 */
    public int code() {
        return code;
    }

    /**
     * 按稳定数据库码解析批量操作类型。
     *
     * @param code 数据库码
     * @return 对应类型
     * @throws IllegalArgumentException 未知码
     */
    public static GroupBatchTaskType fromCode(Integer code) {
        if (code != null) {
            for (GroupBatchTaskType type : values()) {
                if (type.code == code) {
                    return type;
                }
            }
        }
        throw new IllegalArgumentException("未知群批量操作类型: " + code);
    }
}
