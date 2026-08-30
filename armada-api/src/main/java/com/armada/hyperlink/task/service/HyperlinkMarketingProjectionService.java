package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkMarketingStatMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 幂等重算市场聚合，并按页面上限多保留一天/一小时用于迟到事实修正。 */
@Service
public class HyperlinkMarketingProjectionService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int DELETE_BATCH = 5_000;
    private final HyperlinkMarketingStatMapper mapper;
    private final Clock clock;

    @Autowired
    public HyperlinkMarketingProjectionService(HyperlinkMarketingStatMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    HyperlinkMarketingProjectionService(HyperlinkMarketingStatMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 每五分钟重算当前与上一小时，吸收 ACK、点击和失效事件。 */
    public void rebuildRecentHours() {
        ZonedDateTime current = businessNow().truncatedTo(ChronoUnit.HOURS);
        rebuildHours(current.minusHours(1), current.plusHours(1));
    }

    /** 每小时重算今天与昨天的日投影。 */
    public void rebuildRecentDays() {
        LocalDate today = businessNow().toLocalDate();
        rebuildDays(today.minusDays(1), today);
    }

    /** 低峰完整回填页面可查窗口，并执行 90 天/8 天滚动清理。 */
    public void rebuildRetainedWindows() {
        ZonedDateTime currentHour = businessNow().truncatedTo(ChronoUnit.HOURS);
        LocalDate today = currentHour.toLocalDate();
        rebuildDays(today.minusDays(89), today);
        rebuildHours(currentHour.minusDays(8), currentHour.plusHours(1));
        deleteDailyBatches(dateKey(today.minusDays(89)));
        deleteHourlyBatches(currentHour.minusDays(8).toInstant().toEpochMilli());
    }

    private void rebuildDays(LocalDate from, LocalDate to) {
        long allStart = from.atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli();
        long allEnd = to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli();
        List<Long> tenants = mapper.selectProjectionTenantIds(allStart, allEnd);
        long now = clock.millis();
        for (long tenantId : tenants) {
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                long start = day.atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli();
                long end = day.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli();
                mapper.upsertDailyBucket(tenantId, dateKey(day), start, end, now);
            }
        }
    }

    private void rebuildHours(ZonedDateTime from, ZonedDateTime endExclusive) {
        long allStart = from.toInstant().toEpochMilli();
        long allEnd = endExclusive.toInstant().toEpochMilli();
        List<Long> tenants = mapper.selectProjectionTenantIds(allStart, allEnd);
        long now = clock.millis();
        for (long tenantId : tenants) {
            for (ZonedDateTime hour = from; hour.isBefore(endExclusive); hour = hour.plusHours(1)) {
                mapper.upsertHourlyBucket(tenantId, hour.toInstant().toEpochMilli(),
                        hour.plusHours(1).toInstant().toEpochMilli(), now);
            }
        }
    }

    private ZonedDateTime businessNow() {
        return Instant.now(clock).atZone(BUSINESS_ZONE);
    }

    private void deleteDailyBatches(int cutoffDate) {
        while (mapper.deleteDailyBefore(cutoffDate, DELETE_BATCH) == DELETE_BATCH) {
            // 每次提交一小批，直到没有完整批次，避免旧数据长期积压。
        }
    }

    private void deleteHourlyBatches(long cutoffAt) {
        while (mapper.deleteHourlyBefore(cutoffAt, DELETE_BATCH) == DELETE_BATCH) {
            // 每次提交一小批，直到没有完整批次，避免旧数据长期积压。
        }
    }

    private static int dateKey(LocalDate day) {
        return Integer.parseInt(day.format(DateTimeFormatter.BASIC_ISO_DATE));
    }
}
