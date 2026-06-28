package com.armada.marketing.model.dto;

import java.util.List;

/**
 * 建营销任务时的一组账号→群组选择。
 *
 * @param accountId    发言账号 ID
 * @param groupLinkIds 该账号要发送的群入口 ID 列表
 */
public record MarketingSelectionDTO(Long accountId, List<Long> groupLinkIds) {
}
