package com.armada.task.model.enums;

/** 角色账号进入目标群的冻结方式。 */
public enum PullTaskAccountEntryMode {

    /** 账号自行使用目标群链接加入。 */
    JOIN_BY_LINK(1),
    /** 由当前管理账号邀请加入。 */
    MANAGER_INVITE(2),
    /** 站台随拉手的批量成员调用加入。 */
    PULLER_ADD(3),
    /**
     * 站台随建群调用作为初始成员加入；新群模式专有。
     *
     * <p>这样进群的站台必须写 {@code membership_status=IN_GROUP}。
     * {@code PullTaskStationSelectionService} 的可复用判定要求
     * {@code membership_status=NOT_JOINED}，写错会让该站台被后续拉人调用重新选中并重复提交。</p>
     */
    GROUP_CREATE_INITIAL(4);

    private final int code;

    PullTaskAccountEntryMode(int code) {
        this.code = code;
    }

    /** @return 数据库存储值 */
    public int code() {
        return code;
    }

    /** @return 匹配的进入方式；未知值返回 null */
    public static PullTaskAccountEntryMode fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (PullTaskAccountEntryMode value : values()) {
            if (value.code == code) {
                return value;
            }
        }
        return null;
    }
}
