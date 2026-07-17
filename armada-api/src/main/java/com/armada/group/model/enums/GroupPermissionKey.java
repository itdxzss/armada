package com.armada.group.model.enums;

/** 群详情抽屉支持的 WhatsApp 群权限。 */
public enum GroupPermissionKey {

    /** 普通成员是否可以编辑群名称、描述和头像。 */
    EDIT_GROUP_SETTINGS,

    /** 普通成员是否可以发送新消息。 */
    SEND_MESSAGES,

    /** 普通成员是否可以添加其他成员。 */
    ADD_MEMBERS,

    /** 是否允许通过邀请链接加入群。 */
    INVITE_VIA_LINK,

    /** 是否由管理员批准新成员入群。 */
    ADMIN_APPROVE_NEW_MEMBERS
}
