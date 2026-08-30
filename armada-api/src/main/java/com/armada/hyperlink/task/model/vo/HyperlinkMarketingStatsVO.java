package com.armada.hyperlink.task.model.vo;

import java.util.List;

/** 市场分析总览与按发送国×收件国分组的结果。 */
public record HyperlinkMarketingStatsVO(String granularity,
        HyperlinkMarketingMetricVO overview, List<Item> items) {
    /** 趋势按时间桶去重；summary 汇总该国家对的桶内指标。 */
    public record Item(String senderCountryIso2, String recipientCountryIso2,
            HyperlinkMarketingMetricVO summary, List<HyperlinkMarketingMetricVO> series) { }
}
