package com.armada.task.model.enums;

/** 批量拉人参与者类型；与逐号码执行台账 participant_type 一一对应。 */
public enum PullTaskParticipantType {

    /** TXT 料子号码。 */
    MATERIAL(1),

    /** 站台角色账号。 */
    STATION(2);

    private final int code;

    PullTaskParticipantType(int code) {
        this.code = code;
    }

    /** @return 数据库存储值 */
    public int code() {
        return code;
    }
}
