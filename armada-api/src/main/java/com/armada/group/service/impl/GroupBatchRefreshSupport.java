package com.armada.group.service.impl;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.vo.GroupCurrentIdentity;
import com.armada.group.service.GroupBatchAccountThrottle;
import com.armada.group.service.GroupExecutionAccountSelector;
import org.springframework.stereotype.Component;

/**
 * 两类批量刷新执行器共用的基础设施。
 *
 * <p>刷新群链接与获取最新群信息只在"发哪个协议调用"上不同,选号、解析 groupJid、
 * 账号并发闸门与逐项结算完全一致,合成一个组合避免两处漂移。</p>
 *
 * @param selector 执行账号选择器
 * @param groupLinkMapper 当前群身份读取
 * @param throttle 按账号并发闸门
 * @param settlement 逐项独立事务结算
 */
@Component
public record GroupBatchRefreshSupport(
        GroupExecutionAccountSelector selector,
        GroupLinkMapper groupLinkMapper,
        GroupBatchAccountThrottle throttle,
        GroupBatchTaskSettlement settlement) {

    /**
     * 解析目标群 JID。
     *
     * <p>提交阶段只落 group_link_id,执行时才解析 JID,因此这里可能取不到。</p>
     *
     * @param groupLinkId 群入口 ID
     * @return 规范化后的群 JID;未知时为 null
     */
    public String groupJid(Long groupLinkId) {
        GroupCurrentIdentity identity = groupLinkMapper.selectCurrentIdentity(groupLinkId);
        String groupJid = identity == null ? null : identity.groupJid();
        return groupJid == null || groupJid.isBlank() ? null : groupJid.trim();
    }
}
