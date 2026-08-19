package com.armada.task.model.enums;

/**
 * 「群信息设置」一条 {@code group.profile.apply} 命令里的单个设置项。
 *
 * <p>命令是整块下发的，但结果要能落到具体哪一项上：运营看「设置失败」这四个字没法排查，
 * 必须知道是群名没改上还是头像没传上。协议层回传失败时用本枚举标明失败项，业务侧再按项
 * 分派到各自的 {@link PullTaskExecutionReasonCode}。</p>
 *
 * <p><b>声明顺序即协议层的执行顺序</b>（业务确认 2026-08-19）：先设看得见的资料（群名、头像、
 * 描述），再设权限类。多项失败时只回报按此顺序的第一项，所以这个顺序是「第一个失败项」这条
 * 规则的定义依据——协议层两侧已按它钉了断言，**不要重排本枚举**。</p>
 */
public enum PullTaskGroupSettingItem {

    /** 群名称：表单手填，或按勾选取该执行行的料子文件名。 */
    GROUP_NAME,

    /** 群头像：表单上传的图片。 */
    AVATAR,

    /** 群描述（群公告栏的说明文本）。 */
    DESCRIPTION,

    /** 群禁言：是否只允许管理员发言。 */
    MUTE,

    /** 群资料编辑权限：是否允许全体成员改群名、群头像、群描述。 */
    EDIT_PERMISSION,

    /** 加人（邀请链接）权限：是否允许全体成员邀请他人入群。 */
    MEMBER_ADD_PERMISSION,

    /** 入群审批：踩链接进群是否需要管理员批准。 */
    JOIN_APPROVAL,

    /** 限时消息：群消息多久后自动消失。 */
    DISAPPEARING_MESSAGE
}
