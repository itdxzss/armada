package com.armada.marketing.grouppull.model.enums;

/** 单个群组的顺序执行阶段。 */
public enum GroupPullExecutionStage {

    /** 资源分配。 */
    RESOURCE_PREPARATION(1),

    /** 建群号、营销号和料子好友准备。 */
    FRIEND_PREPARATION(2),

    /** 创建 WhatsApp 群组。 */
    CREATE_GROUP(3),

    /** 确认或补加营销账号。 */
    ADD_MARKETER(4),

    /** 添加本群预留料子。 */
    ADD_MATERIALS(5),

    /** 按联动规则设置营销账号管理员。 */
    SET_MARKETER_ADMIN(6),

    /** 设置群组发言权限。 */
    SET_SPEAK_PERMISSION(7),

    /** 查询并保存群组核心信息。 */
    SAVE_GROUP_INFO(8),

    /** 按配置执行建群账号退群。 */
    BUILDER_LEAVE(9),

    /** 幂等结算本次建群结果。 */
    FINALIZE_RESULT(10),

    /** 本次执行已经完成。 */
    COMPLETED(11);

    private final int code;

    GroupPullExecutionStage(int code) {
        this.code = code;
    }

    /** 返回数据库持久化码值。 */
    public int code() {
        return code;
    }

    /** 按数据库码值解析执行阶段。 */
    public static GroupPullExecutionStage fromCode(int code) {
        return switch (code) {
            case 1 -> RESOURCE_PREPARATION;
            case 2 -> FRIEND_PREPARATION;
            case 3 -> CREATE_GROUP;
            case 4 -> ADD_MARKETER;
            case 5 -> ADD_MATERIALS;
            case 6 -> SET_MARKETER_ADMIN;
            case 7 -> SET_SPEAK_PERMISSION;
            case 8 -> SAVE_GROUP_INFO;
            case 9 -> BUILDER_LEAVE;
            case 10 -> FINALIZE_RESULT;
            case 11 -> COMPLETED;
            default -> throw new IllegalArgumentException("未知拉群执行阶段: " + code);
        };
    }
}
