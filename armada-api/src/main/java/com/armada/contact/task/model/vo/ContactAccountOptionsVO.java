package com.armada.contact.task.model.vo;

import com.armada.account.model.vo.AccountGroupOptionVO;
import com.armada.promotion.channel.model.vo.PromotionChannelOptionVO;
import java.util.List;

/**
 * 通讯录任务账号筛选选项，仅包含当前租户的 ID 和名称。
 *
 * @param groups 活跃账号分组，对应 groupIds
 * @param channels 启用推广渠道，对应 channelIds
 */
public record ContactAccountOptionsVO(
        List<AccountGroupOptionVO> groups,
        List<PromotionChannelOptionVO> channels) {
}
