package com.armada.group.service;

import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupInvitePort;

/** 单群详情同步所需协议端口组合。 */
public record GroupMetadataSyncProtocolPorts(
        FixedAccountGroupMetadataPort metadata,
        GroupInvitePort invite) {
}
