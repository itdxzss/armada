package com.armada.group.service;

import com.armada.platform.protocol.port.ContactPort;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import org.springframework.stereotype.Component;

/**
 * 历史群拉人 worker 使用的协议端口组合。
 *
 * <p>该组合只控制构造器参数数量，不转发调用，也不缓存任何协议结果。</p>
 *
 * @param groupJoin    拉手踩邀请链接端口
 * @param contact      联系人预存端口
 * @param participants 群成员添加端口
 */
@Component
public record HistoricalGroupPullProtocolPorts(
        GroupJoinPort groupJoin,
        ContactPort contact,
        GroupParticipantPort participants) {
}
