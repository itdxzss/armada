package com.armada.hyperlink.strategy.model.vo;

import com.armada.hyperlink.task.model.vo.HyperlinkCountryOptionVO;
import com.armada.hyperlink.task.model.vo.HyperlinkIdOptionVO;
import com.armada.hyperlink.task.model.vo.HyperlinkStringOptionVO;
import java.util.List;

/**
 * 策略账号筛选抽屉所需的不含钱包信息的轻量上下文。
 *
 * @param defaultAccountGroupIds 公共组、超链组的稳定业务分组 ID
 * @param groupOptions 当前租户账号分组选项
 * @param countryOptions 启用国家选项
 * @param channelOptions 当前租户启用渠道选项
 * @param protocolOptions 当前租户 PRIVATE 能力协议选项
 */
public record HyperlinkStrategyAccountContextVO(
        List<Long> defaultAccountGroupIds,
        List<HyperlinkIdOptionVO> groupOptions,
        List<HyperlinkCountryOptionVO> countryOptions,
        List<HyperlinkIdOptionVO> channelOptions,
        List<HyperlinkStringOptionVO> protocolOptions) {
}
