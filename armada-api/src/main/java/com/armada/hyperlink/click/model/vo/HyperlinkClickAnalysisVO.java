package com.armada.hyperlink.click.model.vo;

import java.util.List;

/** 超链点击分析结果。 */
public record HyperlinkClickAnalysisVO(
        String mode,
        long totalPhones,
        List<HyperlinkClickAnalysisBucketVO> buckets,
        List<HyperlinkClickAnalysisCountryVO> countries,
        boolean factSourceReady) {
}
