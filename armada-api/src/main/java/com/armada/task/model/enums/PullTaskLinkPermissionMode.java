package com.armada.task.model.enums;

/**
 * 群邀请链接权限。
 *
 * <p><b>已废弃，不再下发协议命令。</b>WhatsApp 底层没有独立的「谁能拿群邀请链接」开关，能设的
 * 群权限只有「谁能发消息」和「谁能编辑群资料」两个，取链接的权限绑在后者上。因此本项已并入
 * {@link PullTaskEditPermissionMode}，「群信息设置」命令只下发 {@code editGroupSettingsAllowed}。</p>
 *
 * <p>枚举与 {@code link_permission_mode} 列保留是为了存量数据与表单回显。不要把它接回协议命令的
 * {@code addMembersAllowed}：加人权限与取链接权限不是一回事，接上等于替运营下发一个他没表达过
 * 的权限变更。</p>
 */
public enum PullTaskLinkPermissionMode {
    /** 所有成员均可邀请。 */
    ALL(1),
    /** 仅管理员可邀请。 */
    ADMIN_ONLY(2);

    private final int code;

    PullTaskLinkPermissionMode(int code) {
        this.code = code;
    }

    /** @return 数据库存储值 */
    public int code() {
        return code;
    }

    /** @return 数据库存储值对应的枚举 */
    public static PullTaskLinkPermissionMode fromCode(int code) {
        for (PullTaskLinkPermissionMode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知群链接权限: " + code);
    }
}
