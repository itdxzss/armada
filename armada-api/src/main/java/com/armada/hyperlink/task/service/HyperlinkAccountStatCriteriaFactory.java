package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.model.dto.HyperlinkAccountStatFilterDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountStatQuery;
import com.armada.hyperlink.task.model.query.HyperlinkAccountStatCriteria;
import com.armada.platform.country.service.CountryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 把外部筛选规范化为只含白名单值的 SQL 条件。 */
@Component
public class HyperlinkAccountStatCriteriaFactory {

    private static final Set<Integer> PAGE_SIZES = Set.of(10, 20, 50, 100, 200);
    private static final Set<String> SORT_FIELDS = Set.of("successNum", "deliveredNum", "failedNum");
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private final CountryService countryService;

    @Autowired
    public HyperlinkAccountStatCriteriaFactory(CountryService countryService) {
        this.countryService = countryService;
    }

    HyperlinkAccountStatCriteriaFactory() {
        this.countryService = null;
    }

    public HyperlinkAccountStatCriteria page(long taskId, HyperlinkAccountStatQuery source,
            long snapshotAt) {
        HyperlinkAccountStatQuery query = source == null ? new HyperlinkAccountStatQuery() : source;
        if (!PAGE_SIZES.contains(query.getPageSize())) {
            throw validation("pageSize 仅支持 10、20、50、100、200");
        }
        HyperlinkAccountStatFilterDTO filter = query.toFilter();
        return build(taskId, filter, query.getOffset(), query.getPageSize(), snapshotAt);
    }

    public HyperlinkAccountStatCriteria export(long taskId, HyperlinkAccountStatFilterDTO source,
            int offset, int batchSize, long snapshotAt) {
        HyperlinkAccountStatFilterDTO filter = source == null
                ? new HyperlinkAccountStatFilterDTO() : source;
        return build(taskId, filter, offset, batchSize, snapshotAt);
    }

    private HyperlinkAccountStatCriteria build(long taskId, HyperlinkAccountStatFilterDTO filter,
            int offset, int pageSize, long snapshotAt) {
        if (taskId <= 0) {
            throw validation("任务 ID 无效");
        }
        validateTime(filter.getStartAt(), filter.getEndAt());
        String country = normalizeCountry(filter.getSenderCountryIso2());
        validateRate(filter.getSuccessRateMin(), filter.getSuccessRateMax());
        String sortField = normalizeSortField(filter.getSortField());
        String sortOrder = normalizeSortOrder(filter.getSortOrder());
        return new HyperlinkAccountStatCriteria(taskId, filter.getStartAt(), filter.getEndAt(),
                country, filter.getSuccessRateMin(), filter.getSuccessRateMax(),
                sortField, sortOrder, Math.max(0, offset), pageSize, snapshotAt);
    }

    private static void validateTime(Long startAt, Long endAt) {
        if ((startAt == null) != (endAt == null)) {
            throw validation("开始时间和结束时间必须同时提供");
        }
        if (startAt != null && (startAt < 0 || endAt < 0 || startAt >= endAt)) {
            throw validation("时间范围必须满足 startAt < endAt");
        }
    }

    private static void validateRate(BigDecimal minimum, BigDecimal maximum) {
        if (minimum != null && (minimum.signum() < 0 || minimum.compareTo(ONE_HUNDRED) > 0)) {
            throw validation("成功率最小值必须在 0 到 100 之间");
        }
        if (maximum != null && (maximum.signum() < 0 || maximum.compareTo(ONE_HUNDRED) > 0)) {
            throw validation("成功率最大值必须在 0 到 100 之间");
        }
        if (minimum != null && maximum != null && minimum.compareTo(maximum) > 0) {
            throw validation("成功率最小值不能大于最大值");
        }
    }

    private String normalizeCountry(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String country = value.trim().toUpperCase(Locale.ROOT);
        if ("UNKNOWN".equals(country)) {
            return country;
        }
        if (!country.matches("[A-Z]{2}")) {
            throw validation("发信国家必须是两位 ISO2 或 UNKNOWN");
        }
        return countryService == null
                ? country : countryService.requireActiveOption(country, false).iso2();
    }

    private static String normalizeSortField(String value) {
        String field = value == null || value.isBlank() ? "successNum" : value.trim();
        if (!SORT_FIELDS.contains(field)) {
            throw validation("不支持的排序字段");
        }
        return field;
    }

    private static String normalizeSortOrder(String value) {
        String order = value == null || value.isBlank()
                ? "desc" : value.trim().toLowerCase(Locale.ROOT);
        if (!"asc".equals(order) && !"desc".equals(order)) {
            throw validation("sortOrder 仅支持 asc 或 desc");
        }
        return order;
    }

    private static BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
}
