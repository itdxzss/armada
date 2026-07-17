package com.armada.group.model.enums;

/**
 * 操作账号在历史群中的当前发言状态。
 */
public enum SpeechState {

    /** 群未开启仅管理员发言,成员可正常发言。 */
    NORMAL,

    /** 群开启仅管理员发言,操作账号是群主或管理员,仍可发言。 */
    ADMIN_CAN_SPEAK,

    /** 群开启仅管理员发言,操作账号是普通成员,不可发言。 */
    CANNOT_SPEAK,

    /** 摘要获取失败、群状态异常或发言事实无法可靠判断。 */
    ABNORMAL
}
