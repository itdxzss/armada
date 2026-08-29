package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkAttributionQuery;
import com.armada.hyperlink.task.model.dto.HyperlinkVisitTrendQuery;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRecipient;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.hyperlink.task.model.vo.HyperlinkAttributionItemVO;
import com.armada.hyperlink.task.model.vo.HyperlinkBanReasonRow;
import com.armada.hyperlink.task.model.vo.HyperlinkBanStatsVO;
import com.armada.hyperlink.task.model.vo.HyperlinkVisitBucketRow;
import com.armada.hyperlink.task.model.vo.HyperlinkVisitTrendVO;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** H6 深度归因、访问趋势和封号分布的只读查询。 */
@Service
public class HyperlinkTaskAnalysisService {
    public static final String SENSITIVE_PERMISSION =
            "tenant:hyperlink_task:attribution_sensitive";
    private static final List<String> MASKED_FIELDS = List.of("ip", "userAgent");

    private final HyperlinkTaskMapper taskMapper;
    private final HyperlinkTaskRuntimeMapper runtimeMapper;
    private final HyperlinkTaskRecipientMapper recipientMapper;
    private final HyperlinkTaskAccountUsageMapper usageMapper;
    private final HyperlinkTaskAuditPort auditPort;

    public HyperlinkTaskAnalysisService(HyperlinkTaskMapper taskMapper,
            HyperlinkTaskRuntimeMapper runtimeMapper,
            HyperlinkTaskRecipientMapper recipientMapper,
            HyperlinkTaskAccountUsageMapper usageMapper,
            HyperlinkTaskAuditPort auditPort) {
        this.taskMapper = taskMapper;
        this.runtimeMapper = runtimeMapper;
        this.recipientMapper = recipientMapper;
        this.usageMapper = usageMapper;
        this.auditPort = auditPort;
    }

    @Transactional
    public PageResult<HyperlinkAttributionItemVO> attribution(long taskId,
            HyperlinkAttributionQuery query, boolean sensitiveVisible,
            long tenantId, long actorUserId) {
        requireTask(taskId);
        if (sensitiveVisible) {
            auditPort.requireAvailable();
            auditPort.record(new HyperlinkTaskAuditPort.AuditEvent(
                    "hl-attribution-read:" + taskId + ':' + actorUserId + ':'
                            + System.nanoTime(),
                    HyperlinkTaskAuditPort.Action.ATTRIBUTION_READ, tenantId,
                    actorUserId, taskId, System.currentTimeMillis()));
        }
        long total = recipientMapper.countClicked(taskId, query.getRecipientPhone(),
                query.getSenderPhone());
        int offset = (query.getPage() - 1) * query.getPageSize();
        List<HyperlinkAttributionItemVO> rows = recipientMapper.selectClickedPage(taskId,
                        query.getRecipientPhone(), query.getSenderPhone(), query.getSortOrder(),
                        offset, query.getPageSize()).stream()
                .map(row -> attributionItem(row, sensitiveVisible)).toList();
        return PageResult.of(rows, query.getPage(), query.getPageSize(), total);
    }

    public HyperlinkVisitTrendVO visitTrend(long taskId, HyperlinkVisitTrendQuery query) {
        requireTask(taskId);
        HyperlinkTaskRuntime runtime = runtimeMapper.selectByTaskId(taskId);
        Long anchor = runtime == null ? null : runtime.getFirstVisitAt();
        if (anchor == null) anchor = recipientMapper.selectFirstVisitAt(taskId);
        if (anchor == null) {
            HyperlinkVisitTrendVO.Summary summary = new HyperlinkVisitTrendVO.Summary(
                    0, 0, runtime == null ? null : runtime.getStartedAt(), null,
                    null, 0, value(runtime == null ? null : runtime.getClickTotal()), 0);
            return new HyperlinkVisitTrendVO(query.getRange(), query.getGranularity(),
                    "UNAVAILABLE_CUMULATIVE_ONLY", summary, List.of(), List.of(), List.of());
        }

        long rangeMs = rangeMs(query.getRange());
        long bucketMs = bucketMs(query.getGranularity());
        int bucketCount = Math.toIntExact(rangeMs / bucketMs);
        long endAt = anchor + rangeMs;
        Map<Integer, Long> uvByBucket = new HashMap<>();
        for (HyperlinkVisitBucketRow row
                : recipientMapper.selectVisitUvBuckets(taskId, anchor, endAt, bucketMs)) {
            if (row.getBucketNo() != null && row.getBucketNo() >= 0
                    && row.getBucketNo() < bucketCount) {
                uvByBucket.put(row.getBucketNo(), value(row.getNewUv()));
            }
        }
        long success = value(runtime == null ? null : runtime.getSuccessNum());
        long cumulative = 0;
        List<HyperlinkVisitTrendVO.SeriesItem> series = new ArrayList<>(bucketCount);
        for (int index = 0; index < bucketCount; index++) {
            long newUv = uvByBucket.getOrDefault(index, 0L);
            cumulative += newUv;
            long start = anchor + index * bucketMs;
            series.add(new HyperlinkVisitTrendVO.SeriesItem(start, start + bucketMs,
                    newUv, cumulative, ratio(cumulative, success), null));
        }
        List<HyperlinkVisitTrendVO.SeriesItem> ranked = series.stream()
                .filter(item -> item.newUv() > 0)
                .sorted(Comparator.comparingLong(HyperlinkVisitTrendVO.SeriesItem::newUv).reversed()
                        .thenComparingLong(HyperlinkVisitTrendVO.SeriesItem::bucketTime))
                .limit(3).toList();
        List<HyperlinkVisitTrendVO.TopPeak> peaks = new ArrayList<>();
        for (int index = 0; index < ranked.size(); index++) {
            var item = ranked.get(index);
            peaks.add(new HyperlinkVisitTrendVO.TopPeak(index + 1, item.bucketTime(),
                    item.bucketEndTime(), item.newUv()));
        }
        HyperlinkVisitTrendVO.SeriesItem peak = ranked.isEmpty() ? null : ranked.get(0);
        long uvTotal = series.isEmpty() ? 0 : series.get(series.size() - 1).cumulativeUv();
        long pvTotal = value(runtime == null ? null : runtime.getClickTotal());
        var summary = new HyperlinkVisitTrendVO.Summary(uvTotal, ratio(uvTotal, success),
                runtime == null ? null : runtime.getStartedAt(), anchor,
                peak == null ? null : peak.bucketTime(), peak == null ? 0 : peak.newUv(),
                pvTotal, average(pvTotal, uvTotal));
        return new HyperlinkVisitTrendVO(query.getRange(), query.getGranularity(),
                "UNAVAILABLE_CUMULATIVE_ONLY", summary,
                series, insights(runtime, anchor, series, peak), peaks);
    }

    public HyperlinkBanStatsVO banStats(long taskId) {
        requireTask(taskId);
        List<HyperlinkBanReasonRow> rows = usageMapper.selectBanReasonStats(taskId);
        long total = rows.stream().mapToLong(row -> value(row.getAccountCount())).sum();
        List<HyperlinkBanStatsVO.Item> items = rows.stream()
                .map(row -> new HyperlinkBanStatsVO.Item(stableReason(row.getReason()),
                        reasonNote(stableReason(row.getReason())), value(row.getAccountCount()),
                        percentage(value(row.getAccountCount()), total)))
                .toList();
        return new HyperlinkBanStatsVO(total, items);
    }

    private void requireTask(long taskId) {
        if (taskMapper.selectById(taskId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "超链任务不存在");
        }
    }

    private HyperlinkAttributionItemVO attributionItem(HyperlinkTaskRecipient row,
            boolean sensitiveVisible) {
        return new HyperlinkAttributionItemVO(row.getId(), row.getRecipientPhoneSnapshot(),
                row.getSenderPhoneSnapshot(), value(row.getClickCount()),
                row.getFirstVisitCountryIso2(), row.getFirstVisitDevice(), row.getFirstVisitOs(),
                row.getFirstVisitBrowser(), row.getFirstVisitLanguage(),
                sensitiveVisible ? ip(row.getFirstVisitIpAddress()) : null,
                sensitiveVisible ? row.getFirstVisitUserAgent() : null,
                row.getFirstVisitAt(), row.getLastVisitAt(), row.getAttributionPurgedAt() != null,
                sensitiveVisible, sensitiveVisible ? List.of() : MASKED_FIELDS);
    }

    private List<HyperlinkVisitTrendVO.Insight> insights(HyperlinkTaskRuntime runtime,
            long anchor, List<HyperlinkVisitTrendVO.SeriesItem> series,
            HyperlinkVisitTrendVO.SeriesItem peak) {
        List<HyperlinkVisitTrendVO.Insight> result = new ArrayList<>();
        if (runtime != null && runtime.getStartedAt() != null) {
            result.add(new HyperlinkVisitTrendVO.Insight("TASK_START", runtime.getStartedAt(),
                    "任务开始发送", null));
        }
        result.add(new HyperlinkVisitTrendVO.Insight("FIRST_VISIT", anchor,
                "出现首次访问", "新增 1 人"));
        HyperlinkVisitTrendVO.SeriesItem surge = firstSurge(series);
        if (surge != null) {
            result.add(new HyperlinkVisitTrendVO.Insight("SURGE_START", surge.bucketTime(),
                    "新增访客开始明显增多", "新增 " + surge.newUv() + " 人"));
        }
        if (peak != null) {
            result.add(new HyperlinkVisitTrendVO.Insight("PEAK", peak.bucketTime(),
                    "UV 高峰", "新增 " + peak.newUv() + " 人"));
        }
        result.sort(Comparator.comparingLong(HyperlinkVisitTrendVO.Insight::eventTime));
        return result;
    }

    private HyperlinkVisitTrendVO.SeriesItem firstSurge(
            List<HyperlinkVisitTrendVO.SeriesItem> series) {
        for (int index = 0; index < series.size(); index++) {
            long current = series.get(index).newUv();
            if (current < 3) continue;
            int start = Math.max(0, index - 3);
            long previous = 0;
            for (int cursor = start; cursor < index; cursor++) previous += series.get(cursor).newUv();
            int count = index - start;
            if (count == 0 || previous == 0 || current >= 2D * previous / count) {
                return series.get(index);
            }
        }
        return null;
    }

    private long rangeMs(String range) {
        return Long.parseLong(range.substring(0, range.length() - 1)) * 3_600_000L;
    }

    private long bucketMs(String granularity) {
        return "30m".equals(granularity) ? 1_800_000L
                : Long.parseLong(granularity.substring(0, granularity.length() - 1)) * 3_600_000L;
    }

    private double ratio(long numerator, long denominator) {
        if (denominator <= 0) return 0;
        return Math.round(numerator * 10_000D / denominator) / 100D;
    }

    private double percentage(long count, long total) {
        if (total <= 0) return 0;
        return Math.round(count * 1_000D / total) / 10D;
    }

    private double average(long numerator, long denominator) {
        if (denominator <= 0) return 0;
        return Math.round(numerator * 100D / denominator) / 100D;
    }

    private String stableReason(String reason) {
        return reason == null || reason.isBlank() ? "未知原因" : reason.trim();
    }

    private String reasonNote(String reason) {
        return switch (reason.toLowerCase(Locale.ROOT)) {
            case "account_block_463" -> "中途禁言，马上封号";
            case "account_offline" -> "中途强制被掐掉，封号";
            case "logged out from another device" -> "从主设备登录出，被强制下线";
            case "primary device was logged out" -> "主设备直接掉了/封了";
            default -> null;
        };
    }

    private String ip(byte[] address) {
        if (address == null) return null;
        try {
            return InetAddress.getByAddress(address).getHostAddress();
        } catch (UnknownHostException ignored) {
            return null;
        }
    }

    private long value(Long value) { return value == null ? 0 : value; }
    private int value(Integer value) { return value == null ? 0 : value; }

}
