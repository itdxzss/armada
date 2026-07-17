package com.armada.group.service;

import com.armada.platform.protocol.port.AccountParticipatingGroupPort;
import com.armada.platform.protocol.port.GroupInvitePort;
import com.armada.platform.protocol.port.GroupMetadataPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import org.springframework.stereotype.Component;

/**
 * 历史群查询与成员操作依赖的协议能力组合。
 *
 * <p>该组合只用于控制构造器参数数量，不转发调用、不缓存协议结果。</p>
 *
 * @param participatingGroups 当前群轻量列表与摘要端口
 * @param metadata            单群完整 metadata 与成员端口
 * @param invite              单群邀请链接端口
 * @param participants        群成员角色变更端口
 */
@Component
public record HistoricalGroupProtocolPorts(
        AccountParticipatingGroupPort participatingGroups,
        GroupMetadataPort metadata,
        GroupInvitePort invite,
        GroupParticipantPort participants) {
}
