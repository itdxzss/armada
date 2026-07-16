package com.armada.platform.protocol.model.enums;

/** 群成员管理动作。 */
public enum GroupParticipantAction {

    /** 将普通成员提升为管理员。 */
    PROMOTE("promote"),

    /** 将管理员降为普通成员。 */
    DEMOTE("demote"),

    /** 将成员移出群组。 */
    REMOVE("remove");

    private final String wireValue;

    GroupParticipantAction(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
