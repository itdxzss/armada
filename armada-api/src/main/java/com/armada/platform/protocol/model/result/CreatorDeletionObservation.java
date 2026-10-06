package com.armada.platform.protocol.model.result;
/** 全部来自新鲜协议查询的证据；结构不完整绝不能解释为空创建者。 */
public record CreatorDeletionObservation(String groupJid, long creation, String creator,
        String creatorPN, boolean managerPresent, boolean managerAdmin,
        boolean creatorPresent, boolean registrationKnown, boolean registered,
        boolean structureComplete, long queriedAt) {
    /** 原建群者已不在成员且创建者两个字段均清空。 */
    public boolean creatorCleared() {
        return structureComplete && !creatorPresent && creator != null && creator.isEmpty()
                && creatorPN != null && creatorPN.isEmpty();
    }
}
