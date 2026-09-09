package com.armada.platform.protocol.model.command;
/** 查询当前账号云端通讯录的一页。 */
public record CloudContactsQuery(ProtocolAccountRef account, String cursor) {}
