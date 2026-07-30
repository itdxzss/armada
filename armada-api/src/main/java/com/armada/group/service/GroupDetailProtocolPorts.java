package com.armada.group.service;

import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupParticipantPort;
import com.armada.platform.protocol.port.GroupProfilePort;
import com.armada.platform.protocol.port.GroupSettingsPort;
import org.springframework.stereotype.Component;

/**
 * 群详情业务编排使用的协议能力端口集合。
 *
 * <p>群详情抽屉同时需要元数据读取、群资料修改、群设置修改和成员变更四类协议能力。
 * 本 record 只负责把这些同一基础设施边界的依赖组合为一个构造器参数，避免
 * {@code GroupDetailServiceImpl} 构造器超过项目参数数量限制；它不转发调用、不缓存状态，
 * 也不把四个端口合并成大而全的协议客户端。业务域仍只依赖 platform 的 port，
 * 不直接依赖 HTTP Adapter 或协议 wire DTO。</p>
 *
 * @param metadata     读取群元数据、实时权限、限时消息和成员快照的端口
 * @param profile      修改真实群名称、头像及回读头像 URL 的端口
 * @param settings     修改限时消息和群权限设置的端口
 * @param participants 批量升降管理员或移除成员的端口
 */
@Component
public record GroupDetailProtocolPorts(
        FixedAccountGroupMetadataPort metadata,
        GroupProfilePort profile,
        GroupSettingsPort settings,
        GroupParticipantPort participants) {
}
