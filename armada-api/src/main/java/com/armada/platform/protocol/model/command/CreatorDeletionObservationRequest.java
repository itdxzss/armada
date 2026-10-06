package com.armada.platform.protocol.model.command;
/** 接管管理账号独立读取群资料及原建群号码注册状态。 */
public record CreatorDeletionObservationRequest(ProtocolAccountRef manager,
        ProtocolAccountRef creator, String groupJid) { }
