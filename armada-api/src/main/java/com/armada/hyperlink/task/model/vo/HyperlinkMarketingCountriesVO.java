package com.armada.hyperlink.task.model.vo;

import java.util.List;

/** 当前筛选窗口内出现过的国家选项；ZZ 未知值不进入下拉。 */
public record HyperlinkMarketingCountriesVO(
        List<String> senderCountryIso2,
        List<String> recipientCountryIso2) {
}
