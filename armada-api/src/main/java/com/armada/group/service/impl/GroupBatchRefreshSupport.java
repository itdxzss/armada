package com.armada.group.service.impl;

import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.vo.GroupCurrentIdentity;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.shared.security.DataScopeAccess;
import org.springframework.stereotype.Component;

/**
 * 批量群快照派发共用的选号与群身份解析基础设施。
 *
 * <p>刷新群链接与获取最新群信息只在请求 scope 上不同；选号与 groupJid 解析保持单口径。</p>
 *
 * @param selector 执行账号选择器
 * @param groupLinkMapper 当前群身份读取
 */
@Component
public record GroupBatchRefreshSupport(
        GroupExecutionAccountSelector selector,
        GroupLinkMapper groupLinkMapper) {

    /**
     * 解析目标群 JID。
     *
     * <p>提交阶段只落 group_link_id,执行时才解析 JID,因此这里可能取不到。</p>
     *
     * @param groupLinkId 群入口 ID
     * @return 规范化后的群 JID;未知时为 null
     */
    public String groupJid(Long groupLinkId) {
        GroupCurrentIdentity identity = groupLinkMapper.selectCurrentIdentity(
                groupLinkId, DataScopeAccess.requireCurrent());
        String groupJid = identity == null ? null : identity.groupJid();
        return groupJid == null || groupJid.isBlank() ? null : groupJid.trim();
    }
}
