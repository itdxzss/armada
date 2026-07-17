package com.armada.marketing.model.dto;

import java.util.List;

/**
 * 建营销任务时的一组账号目标选择。
 *
 * @param accountId    发言账号 ID
 * @param targetScope  目标维度:GROUP_FIXED=按已选群组发送,ACCOUNT_DYNAMIC=每轮读取该账号当前全部群
 * @param groupLinkIds 固定群组维度下该账号要发送的群入口 ID 列表
 */
public record MarketingSelectionDTO(Long accountId, String targetScope, List<Long> groupLinkIds) {

    /**
     * 兼容旧前端和旧测试:没有显式 targetScope 时,带 groupLinkIds 的请求仍按固定群组处理。
     */
    public MarketingSelectionDTO(Long accountId, List<Long> groupLinkIds) {
        this(accountId, null, groupLinkIds);
    }
}
