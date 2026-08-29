package com.armada.hyperlink.click.service.impl;

import com.armada.hyperlink.click.model.dto.HyperlinkClickAnalysisExportDTO;
import com.armada.hyperlink.click.model.dto.HyperlinkClickAnalysisQuery;
import com.armada.hyperlink.click.model.enums.HyperlinkClickAnalysisMode;
import com.armada.hyperlink.click.model.vo.HyperlinkClickAnalysisBucketVO;
import com.armada.hyperlink.click.model.vo.HyperlinkClickAnalysisVO;
import com.armada.hyperlink.click.service.HyperlinkClickAnalysisService;
import com.armada.hyperlink.data.model.vo.DataPackageExportFile;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 点击分析参数和响应合同实现。
 *
 * <p>当前系统尚无超链任务发送明细与点击事件写入方，因此只返回明确标记的真实空结果。
 * 写入方接入时保持控制器合同不变，在本服务内替换为事实聚合。</p>
 */
@Service
public class HyperlinkClickAnalysisServiceImpl implements HyperlinkClickAnalysisService {

    private static final long MAX_RANGE_MILLIS = Duration.ofDays(90).toMillis();
    private static final List<Integer> DEFAULT_THRESHOLDS = List.of(5, 10, 15, 20);
    private static final Pattern ISO2_PATTERN = Pattern.compile("^[A-Z]{2}$");

    /** {@inheritDoc} */
    @Override
    public HyperlinkClickAnalysisVO analyze(
            HyperlinkClickAnalysisMode mode,
            HyperlinkClickAnalysisQuery query) {
        HyperlinkClickAnalysisMode normalizedMode = requireMode(mode);
        HyperlinkClickAnalysisQuery normalized = requireQuery(query);
        validateRange(normalized.getDateFrom(), normalized.getDateTo());
        validateDimension(normalized.getDimension());
        normalizeCountry(normalized.getCountryIso2());
        List<Integer> thresholds = normalizeThresholds(
                normalizedMode, normalized.getThresholds());
        List<HyperlinkClickAnalysisBucketVO> buckets = thresholds.stream()
                .map(threshold -> new HyperlinkClickAnalysisBucketVO(
                        threshold, 0, BigDecimal.ZERO))
                .toList();
        return new HyperlinkClickAnalysisVO(
                normalizedMode.apiValue(), 0, buckets, List.of(), false);
    }

    /** {@inheritDoc} */
    @Override
    public DataPackageExportFile export(
            HyperlinkClickAnalysisMode mode,
            HyperlinkClickAnalysisExportDTO request) {
        HyperlinkClickAnalysisMode normalizedMode = requireMode(mode);
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "导出参数不能为空");
        }
        validateRange(request.dateFrom(), request.dateTo());
        int threshold = requireThreshold(normalizedMode, request.threshold());
        normalizeCountry(request.countryIso2());
        if (!"txt".equalsIgnoreCase(request.format())) {
            throw new BusinessException(ErrorCode.VALIDATION, "点击分析仅支持导出 txt");
        }
        String country = StringUtils.hasText(request.countryIso2())
                ? "_" + request.countryIso2().trim().toUpperCase(Locale.ROOT) : "";
        return new DataPackageExportFile(
                "超链点击分析_" + normalizedMode.exportLabel() + "_" + threshold
                        + country + "_" + System.currentTimeMillis() + ".txt",
                "text/plain;charset=UTF-8",
                new byte[0],
                0);
    }

    private static HyperlinkClickAnalysisQuery requireQuery(HyperlinkClickAnalysisQuery query) {
        if (query == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "分析参数不能为空");
        }
        return query;
    }

    private static HyperlinkClickAnalysisMode requireMode(HyperlinkClickAnalysisMode mode) {
        if (mode == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "点击分析类型不能为空");
        }
        return mode;
    }

    private static void validateRange(Long dateFrom, Long dateTo) {
        if (dateFrom == null || dateTo == null || dateFrom < 0 || dateTo < dateFrom) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择正确的分析时间范围");
        }
        if (dateTo - dateFrom > MAX_RANGE_MILLIS) {
            throw new BusinessException(ErrorCode.VALIDATION, "一次最多分析 90 天");
        }
    }

    private static void validateDimension(String dimension) {
        if (StringUtils.hasText(dimension)
                && !"recipient_country".equals(dimension.trim())) {
            throw new BusinessException(ErrorCode.VALIDATION, "分析分组维度不合法");
        }
    }

    private static String normalizeCountry(String countryIso2) {
        if (!StringUtils.hasText(countryIso2)) {
            return null;
        }
        String normalized = countryIso2.trim().toUpperCase(Locale.ROOT);
        if (!ISO2_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION, "国家筛选必须为 ISO2");
        }
        return normalized;
    }

    private static List<Integer> normalizeThresholds(
            HyperlinkClickAnalysisMode mode, String values) {
        if (!StringUtils.hasText(values)) {
            return DEFAULT_THRESHOLDS;
        }
        Set<Integer> normalized = new LinkedHashSet<>();
        for (String value : values.split(",", -1)) {
            if (!value.trim().matches("^[0-9]+$")) {
                String message = mode == HyperlinkClickAnalysisMode.NEVER_CLICK
                        ? "从来不点阈值必须为正整数" : "点击率阈值必须为正整数";
                throw new BusinessException(ErrorCode.VALIDATION, message);
            }
            normalized.add(requireThreshold(mode, Integer.valueOf(value.trim())));
        }
        if (normalized.isEmpty() || normalized.size() > 10) {
            throw new BusinessException(ErrorCode.VALIDATION, "阈值档位必须为 1 至 10 个");
        }
        List<Integer> result = new ArrayList<>(normalized);
        result.sort(Integer::compareTo);
        return List.copyOf(result);
    }

    private static int requireThreshold(
            HyperlinkClickAnalysisMode mode, Integer threshold) {
        if (threshold == null || threshold <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "分析阈值必须大于 0");
        }
        if (mode == HyperlinkClickAnalysisMode.UV_RATIO && threshold > 100) {
            throw new BusinessException(ErrorCode.VALIDATION, "点击率阈值不能大于 100%");
        }
        return threshold;
    }
}
