package com.armada.hyperlink.task.model.vo;

import java.util.List;

/** 访问趋势。PV 总量真实，历史分桶在无逐次访问事实时明确不可用。 */
public record HyperlinkVisitTrendVO(
        String range,
        String granularity,
        String pvBucketMode,
        Summary summary,
        List<SeriesItem> series,
        List<Insight> insights,
        List<TopPeak> topPeaks) {

    public record Summary(long uvTotal, double clickRate, Long taskStartAt,
            Long firstVisitAt, Long peakBucketTime, long peakNewUv,
            long pvTotal, double pvPerUv) { }

    public record SeriesItem(long bucketTime, long bucketEndTime, long newUv,
            long cumulativeUv, double cumulativeClickRate, Long pv) { }

    public record Insight(String eventType, long eventTime, String title, String detail) { }

    public record TopPeak(int rank, long bucketTime, long bucketEndTime, long newUv) { }
}
