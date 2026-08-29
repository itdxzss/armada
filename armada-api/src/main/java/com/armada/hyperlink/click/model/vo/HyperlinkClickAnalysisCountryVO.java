package com.armada.hyperlink.click.model.vo;

import java.util.List;

/** 按收件人国家分组的点击分析结果。 */
public record HyperlinkClickAnalysisCountryVO(
        String countryIso2,
        long totalPhones,
        List<HyperlinkClickAnalysisBucketVO> buckets) {
}
