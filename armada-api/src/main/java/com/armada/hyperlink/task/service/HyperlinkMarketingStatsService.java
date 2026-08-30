package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkMarketingStatMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkMarketingStatsQuery;
import com.armada.hyperlink.task.model.query.HyperlinkMarketingStatCriteria;
import com.armada.hyperlink.task.model.vo.HyperlinkMarketingCountriesVO;
import com.armada.hyperlink.task.model.vo.HyperlinkMarketingMetricVO;
import com.armada.hyperlink.task.model.vo.HyperlinkMarketingStatRow;
import com.armada.hyperlink.task.model.vo.HyperlinkMarketingStatsVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 查询日/小时市场投影；在线请求不回源扫描 recipient。 */
@Service
public class HyperlinkMarketingStatsService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter HOUR_INPUT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter HOUR_OUTPUT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00:00").withZone(BUSINESS_ZONE);
    private static final int MAX_DAY_WINDOW = 90;
    private static final long MAX_HOUR_WINDOW_HOURS = 7 * 24L;

    private final HyperlinkMarketingStatMapper mapper;

    public HyperlinkMarketingStatsService(HyperlinkMarketingStatMapper mapper) {
        this.mapper = mapper;
    }

    /** 返回全局精确总览、国家对汇总和趋势。 */
    @Transactional(readOnly = true)
    public HyperlinkMarketingStatsVO stats(HyperlinkMarketingStatsQuery query) {
        HyperlinkMarketingStatCriteria criteria = criteria(query);
        List<HyperlinkMarketingStatRow> rows = "day".equals(criteria.granularity())
                ? mapper.selectDaily(criteria) : mapper.selectHourly(criteria);
        Map<CountryPair, List<HyperlinkMarketingStatRow>> groups = new LinkedHashMap<>();
        for (HyperlinkMarketingStatRow row : rows) {
            groups.computeIfAbsent(new CountryPair(row.getSenderCountryIso2(),
                    row.getRecipientCountryIso2()), ignored -> new ArrayList<>()).add(row);
        }
        List<HyperlinkMarketingStatsVO.Item> items = new ArrayList<>(groups.size());
        for (var entry : groups.entrySet()) {
            List<HyperlinkMarketingMetricVO> series = entry.getValue().stream()
                    .map(row -> metric(criteria.granularity(), row)).toList();
            items.add(new HyperlinkMarketingStatsVO.Item(entry.getKey().sender(),
                    entry.getKey().recipient(), summary(entry.getValue()), series));
        }
        HyperlinkMarketingStatRow overviewRow = mapper.selectExactOverview(criteria);
        HyperlinkMarketingMetricVO overview = overviewRow == null
                ? metric(null, 0, 0, 0, 0, 0, 0, null)
                : metricFromRow(null, overviewRow);
        return new HyperlinkMarketingStatsVO(criteria.granularity(), overview,
                List.copyOf(items));
    }

    /** 返回所选时间窗口内出现过的国家；未知占位 ZZ 不进入下拉。 */
    @Transactional(readOnly = true)
    public HyperlinkMarketingCountriesVO countries(HyperlinkMarketingStatsQuery query) {
        List<HyperlinkMarketingStatRow> rows = mapper.selectCountries(criteria(query));
        TreeSet<String> senders = new TreeSet<>();
        TreeSet<String> recipients = new TreeSet<>();
        for (HyperlinkMarketingStatRow row : rows) {
            addCountry(senders, row.getSenderCountryIso2());
            addCountry(recipients, row.getRecipientCountryIso2());
        }
        return new HyperlinkMarketingCountriesVO(List.copyOf(senders), List.copyOf(recipients));
    }

    HyperlinkMarketingStatCriteria criteria(HyperlinkMarketingStatsQuery query) {
        if (query == null) throw validation("查询参数不能为空");
        long tenantId = requireTenant();
        String granularity = normalized(query.getGranularity());
        validateDimensions(query);
        return switch (granularity) {
            case "day" -> dailyCriteria(tenantId, query);
            case "hour" -> hourlyCriteria(tenantId, query);
            default -> throw validation("granularity 仅支持 day 或 hour");
        };
    }

    private HyperlinkMarketingStatCriteria dailyCriteria(long tenantId,
            HyperlinkMarketingStatsQuery query) {
        try {
            LocalDate from = LocalDate.parse(required(query.getDateFrom(), "dateFrom"));
            LocalDate to = LocalDate.parse(required(query.getDateTo(), "dateTo"));
            long days = ChronoUnit.DAYS.between(from, to) + 1;
            if (days < 1 || days > MAX_DAY_WINDOW) {
                throw validation("日粒度查询窗口最多 90 天");
            }
            long start = from.atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli();
            long end = to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant().toEpochMilli();
            return criteria(tenantId, "day", start, end,
                    Integer.parseInt(from.format(DateTimeFormatter.BASIC_ISO_DATE)),
                    Integer.parseInt(to.format(DateTimeFormatter.BASIC_ISO_DATE)), query);
        } catch (DateTimeParseException exception) {
            throw validation("日粒度时间格式应为 yyyy-MM-dd");
        }
    }

    private HyperlinkMarketingStatCriteria hourlyCriteria(long tenantId,
            HyperlinkMarketingStatsQuery query) {
        try {
            LocalDateTime from = LocalDateTime.parse(required(query.getDateFrom(), "dateFrom"), HOUR_INPUT);
            LocalDateTime to = LocalDateTime.parse(required(query.getDateTo(), "dateTo"), HOUR_INPUT);
            LocalDateTime startHour = from.truncatedTo(ChronoUnit.HOURS);
            LocalDateTime endHour = to.truncatedTo(ChronoUnit.HOURS);
            long buckets = ChronoUnit.HOURS.between(startHour, endHour) + 1;
            if (buckets < 1 || buckets > MAX_HOUR_WINDOW_HOURS) {
                throw validation("小时粒度查询窗口最多 7 天");
            }
            long start = startHour.atZone(BUSINESS_ZONE).toInstant().toEpochMilli();
            long end = endHour.plusHours(1).atZone(BUSINESS_ZONE).toInstant().toEpochMilli();
            return criteria(tenantId, "hour", start, end, 0, 0, query);
        } catch (DateTimeParseException exception) {
            throw validation("小时粒度时间格式应为 yyyy-MM-dd HH:mm:ss");
        }
    }

    private HyperlinkMarketingStatCriteria criteria(long tenantId, String granularity,
            long start, long end, int dateFrom, int dateTo, HyperlinkMarketingStatsQuery query) {
        return new HyperlinkMarketingStatCriteria(tenantId, granularity, start, end,
                dateFrom, dateTo, query.getTaskType(), country(query.getSenderCountryIso2()),
                country(query.getRecipientCountryIso2()), query.getAccountType(),
                deviceOs(query.getDeviceOs()), query.getShortLinkEnabled());
    }

    private void validateDimensions(HyperlinkMarketingStatsQuery query) {
        if (query.getTaskType() != null && (query.getTaskType() < 1 || query.getTaskType() > 3)) {
            throw validation("taskType 仅支持 1、2、3");
        }
        if (query.getAccountType() != null
                && query.getAccountType() != 1 && query.getAccountType() != 2) {
            throw validation("accountType 仅支持 1、2");
        }
        country(query.getSenderCountryIso2());
        country(query.getRecipientCountryIso2());
        deviceOs(query.getDeviceOs());
    }

    private HyperlinkMarketingMetricVO summary(List<HyperlinkMarketingStatRow> rows) {
        long send = 0, success = 0, delivered = 0, used = 0, banned = 0, clicks = 0;
        Long updated = null;
        for (HyperlinkMarketingStatRow row : rows) {
            send += value(row.getSendTotal());
            success += value(row.getSuccessNum());
            delivered += value(row.getDeliveredNum());
            used += value(row.getUsedAccountCount());
            banned += value(row.getBannedAccountCount());
            clicks += value(row.getClickUvNum());
            if (row.getUpdatedAt() != null && (updated == null || row.getUpdatedAt() > updated)) {
                updated = row.getUpdatedAt();
            }
        }
        return metric(null, send, success, delivered, used, banned, clicks, updated);
    }

    private HyperlinkMarketingMetricVO metric(String granularity, HyperlinkMarketingStatRow row) {
        String time = "day".equals(granularity)
                ? dailyTime(row.getStatTime()) : HOUR_OUTPUT.format(Instant.ofEpochMilli(row.getStatTime()));
        return metric(time, value(row.getSendTotal()), value(row.getSuccessNum()),
                value(row.getDeliveredNum()), value(row.getUsedAccountCount()),
                value(row.getBannedAccountCount()), value(row.getClickUvNum()), row.getUpdatedAt());
    }

    private HyperlinkMarketingMetricVO metricFromRow(String time, HyperlinkMarketingStatRow row) {
        return metric(time, value(row.getSendTotal()), value(row.getSuccessNum()),
                value(row.getDeliveredNum()), value(row.getUsedAccountCount()),
                value(row.getBannedAccountCount()), value(row.getClickUvNum()), row.getUpdatedAt());
    }

    private HyperlinkMarketingMetricVO metric(String time, long send, long success,
            long delivered, long used, long banned, long clicks, Long updated) {
        return new HyperlinkMarketingMetricVO(time, send, success, ratio(success, send),
                delivered, ratio(delivered, success), used, banned, ratio(banned, used),
                ratio(success, used), clicks, updated);
    }

    private static String dailyTime(Long value) {
        String digits = String.format(Locale.ROOT, "%08d", value == null ? 0 : value);
        return LocalDate.parse(digits, DateTimeFormatter.BASIC_ISO_DATE).toString();
    }

    private static void addCountry(TreeSet<String> target, String value) {
        if (value != null && !"ZZ".equals(value)) target.add(value);
    }

    private static String country(String value) {
        String normalized = normalized(value);
        if (normalized == null) return null;
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{2}")) throw validation("国家代码必须是 ISO2");
        return normalized;
    }

    private static Integer deviceOs(String value) {
        String normalized = normalized(value);
        if (normalized == null) return null;
        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "android" -> 1;
            case "iphone" -> 2;
            default -> throw validation("deviceOs 仅支持 android 或 iphone");
        };
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String required(String value, String field) {
        String normalized = normalized(value);
        if (normalized == null) throw validation(field + " 必填");
        return normalized;
    }

    private static long requireTenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0) throw new BusinessException(ErrorCode.TENANT_MISSING);
        return tenantId;
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0D : (double) numerator / denominator;
    }

    private static long value(Long value) { return value == null ? 0 : value; }
    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
    private record CountryPair(String sender, String recipient) { }
}
