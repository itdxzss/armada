package com.armada.platform.protocol.model.command;
/** 独立互存任务命令引用，Outbox 不存号码与凭据。 */
public record ProtocolMutualContactCommandRequest(Long tenantId, Long taskId, Long itemId, ProtocolAccountRef actor) {
    public static final String SOURCE = "account_group_mutual_contact";
    public static final String AGGREGATE = "ACCOUNT_MUTUAL_CONTACT_ITEM";
}
