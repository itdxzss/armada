package com.armada.group.model.enums;

/** 群组列表多选控制关系；创建者是否在群与超级管理员角色分别判断。 */
public enum GroupControlRelation {
    /** 我方未删除账号当前在群且为超级管理员。 */
    CONTROLLED_OWNER,
    /** 我方在群管理员存在，完整成员事实确认建群人不在群。 */
    CONTROLLED_ADMIN_CREATOR_ABSENT,
    /** 建群人当前在群，且该账号不属于本租户的未删除账号。 */
    EXTERNAL_CREATOR_PRESENT,
    /** 缺少创建者身份或成员事实，无法确认建群人是否在群。 */
    UNKNOWN
}
