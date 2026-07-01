package com.armada.resource.service.impl;

import com.armada.platform.country.mapper.CountryMapper;
import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.model.IpProxyAllocationMode;
import com.armada.resource.model.IpProxyResourceRisk;
import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.ProxyOwnership;
import com.armada.resource.model.ProxyProtocol;
import com.armada.resource.model.dto.IpProxyCountrySampleCheckDTO;
import com.armada.resource.model.dto.IpProxyStatsCountryQuery;
import com.armada.resource.model.dto.IpProxyStatsDetailQuery;
import com.armada.resource.model.entity.IpProxy;
import com.armada.resource.model.vo.IpProxyCheckResultVO;
import com.armada.resource.model.vo.IpProxyCountrySampleCheckVO;
import com.armada.resource.model.vo.IpProxyCountrySampleStatsVO;
import com.armada.resource.model.vo.IpProxyCountryStatsRow;
import com.armada.resource.model.vo.IpProxyCountryStatsVO;
import com.armada.resource.model.vo.IpProxyStatsDetailRow;
import com.armada.resource.model.vo.IpProxyStatsDetailVO;
import com.armada.resource.model.vo.IpProxyStatsExportFile;
import com.armada.resource.model.vo.IpProxyStatsSummaryVO;
import com.armada.resource.service.IpProxyService;
import com.armada.resource.service.IpProxyStatsService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * IP 数据统计业务实现。
 *
 * <p>Mapper 负责 SQL 聚合和分页;Service 只补比例、风险标签和枚举展示值。</p>
 */
@Service
public class IpProxyStatsServiceImpl implements IpProxyStatsService {

    /** 百分比换算常量。 */
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** 比率统一保留两位小数。 */
    private static final int RATE_SCALE = 2;

    /** 国家级抽样检测内部按既有批量检测能力分批执行;这不是用户可见的抽样数量上限。 */
    private static final int SAMPLE_CHECK_BATCH_SIZE = 20;

    /** 国家 IP 导出内容类型。 */
    private static final String EXPORT_CONTENT_TYPE = "text/plain;charset=UTF-8";

    /** IP 代理 Mapper,承载统计 SQL 聚合和分页。 */
    private final IpProxyMapper mapper;

    /** IP 代理业务服务,复用既有批量检测和检测落库逻辑。 */
    private final IpProxyService ipProxyService;

    /** 国家主数据 Mapper,维护国家级抽检状态。 */
    private final CountryMapper countryMapper;

    /**
     * 构造 IP 数据统计服务。
     *
     * @param mapper IP 代理 Mapper
     * @param ipProxyService IP 代理业务服务
     * @param countryMapper 国家主数据 Mapper
     */
    public IpProxyStatsServiceImpl(IpProxyMapper mapper, IpProxyService ipProxyService, CountryMapper countryMapper) {
        this.mapper = mapper;
        this.ipProxyService = ipProxyService;
        this.countryMapper = countryMapper;
    }

    /**
     * 查询本租户 IP 池总览统计。
     *
     * <p>Mapper 正常会返回单行聚合结果;这里保留 null 兜底,避免空表或异常映射导致前端拿到空 data。</p>
     *
     * @return 总览统计,计数为空时按 0 返回
     */
    @Override
    public IpProxyStatsSummaryVO summary() {
        IpProxyStatsSummaryVO summary = mapper.selectStatsSummary();
        return summary == null
                ? new IpProxyStatsSummaryVO(0L, 0L, 0L, 0L, 0L, 0L, 0L)
                : summary;
    }

    /**
     * 分页查询国家/地区维度统计。
     *
     * <p>风险筛选和分页在 SQL 层完成;Service 只把聚合计数补齐为比率和风险展示值。</p>
     *
     * @param query 查询条件;允许为 null,按默认分页处理
     * @return 国家/地区统计分页结果
     */
    @Override
    public PageResult<IpProxyCountryStatsVO> countries(IpProxyStatsCountryQuery query) {
        IpProxyStatsCountryQuery normalized = query == null ? new IpProxyStatsCountryQuery() : query;
        validateCountryQuery(normalized);
        long total = mapper.countCountryStats(normalized);
        List<IpProxyCountryStatsVO> rows = total == 0
                ? List.of()
                : mapper.selectCountryStatsPage(normalized).stream().map(this::toCountryVO).toList();
        return PageResult.of(rows, normalized.getPage(), normalized.getPageSize(), total);
    }

    /**
     * 分页查询指定国家/地区下的 IP 明细。
     *
     * <p>明细行不返回密码;Service 只补协议、状态、归属的展示 label。</p>
     *
     * @param region 国家/地区中文快照,不能为空
     * @param query  查询条件;允许为 null,按默认分页处理
     * @return 指定国家/地区的 IP 明细分页结果
     * @throws BusinessException 国家/地区为空、协议码或状态码非法时抛出
     */
    @Override
    public PageResult<IpProxyStatsDetailVO> regionProxies(String region, IpProxyStatsDetailQuery query) {
        if (!StringUtils.hasText(region)) {
            throw new BusinessException(ErrorCode.VALIDATION, "国家/地区不能为空");
        }
        String normalizedRegion = region.trim();
        IpProxyStatsDetailQuery normalized = query == null ? new IpProxyStatsDetailQuery() : query;
        validateDetailQuery(normalized);
        long total = mapper.countStatsDetail(normalizedRegion, normalized);
        List<IpProxyStatsDetailVO> rows = total == 0
                ? List.of()
                : mapper.selectStatsDetailPage(normalizedRegion, normalized).stream().map(this::toDetailVO).toList();
        return PageResult.of(rows, normalized.getPage(), normalized.getPageSize(), total);
    }

    /**
     * 导出指定国家/地区下的全部未删除 IP。
     *
     * <p>导出行沿用导入格式 {@code host:port:username:password},便于同一批代理重新导入或迁移。</p>
     */
    @Override
    public IpProxyStatsExportFile exportRegionProxies(String region) {
        if (!StringUtils.hasText(region)) {
            throw new BusinessException(ErrorCode.VALIDATION, "国家/地区不能为空");
        }
        String normalizedRegion = region.trim();
        List<IpProxy> rows = mapper.selectStatsDetailExportRows(normalizedRegion);
        String content = toExportContent(rows);
        return new IpProxyStatsExportFile(
                "ip-proxies-" + safeFilenamePart(normalizedRegion) + ".txt",
                EXPORT_CONTENT_TYPE,
                content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 对指定国家/地区随机抽样执行 IP 检测。
     *
     * <p>IP 级检测时间仍由 {@link IpProxyService#checkProxies(List)} 写入 ip_proxy;
     * 国家级最近抽检时间在本方法确认本次抽样检测完成后写入 country。</p>
     */
    @Override
    public IpProxyCountrySampleCheckVO sampleCheckRegion(String region, IpProxyCountrySampleCheckDTO request) {
        if (!StringUtils.hasText(region)) {
            throw new BusinessException(ErrorCode.VALIDATION, "国家/地区不能为空");
        }
        int sampleCount = validateSampleCount(request);
        String normalizedRegion = region.trim();
        IpProxyCountrySampleStatsVO stats = loadCountrySampleStats(normalizedRegion);
        long totalIpCount = nullToZero(stats.totalIpCount());
        if (totalIpCount <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "当前国家暂无 IP 可检测");
        }
        if (sampleCount > totalIpCount) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "抽样检测数量不能超过当前国家 IP 总数量 " + totalIpCount);
        }
        List<Long> sampleIds = mapper.selectSampleActiveIdsByRegion(normalizedRegion, sampleCount);
        if (sampleIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "当前国家暂无 IP 可检测");
        }
        if (sampleIds.size() < sampleCount) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "抽样检测数量不能超过当前国家 IP 总数量 " + sampleIds.size());
        }
        List<IpProxyCheckResultVO> results = checkSampleIds(sampleIds);
        long checkedAt = System.currentTimeMillis();
        int updatedRows = countryMapper.updateLastIpSampleCheckAtByNameZh(normalizedRegion, checkedAt);
        if (updatedRows != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "国家不存在或未启用 IP 管理: " + normalizedRegion);
        }
        return new IpProxyCountrySampleCheckVO(normalizedRegion, results.size(), checkedAt, results);
    }

    /**
     * 查询指定国家/地区的抽样检测弹框统计。
     *
     * <p>该统计不继承列表上的协议、来源、分配方式筛选,始终按当前国家全部未软删 IP 计算。</p>
     */
    @Override
    public IpProxyCountrySampleStatsVO countrySampleStats(String region) {
        if (!StringUtils.hasText(region)) {
            throw new BusinessException(ErrorCode.VALIDATION, "国家/地区不能为空");
        }
        return loadCountrySampleStats(region.trim());
    }

    /**
     * 校验国家/地区统计查询中的枚举型筛选值。
     *
     * @param query 已归一化的查询对象
     * @throws BusinessException 协议码或风险值非法时抛出
     */
    private void validateCountryQuery(IpProxyStatsCountryQuery query) {
        if (query.getProtocol() != null) {
            ProxyProtocol.fromCode(query.getProtocol());
        }
        normalizeAllocationMode(query);
        IpProxyResourceRisk.validateFilter(query.getRisk());
    }

    /**
     * 校验国家/地区明细查询中的枚举型筛选值。
     *
     * @param query 已归一化的查询对象
     * @throws BusinessException 协议码或状态码非法时抛出
     */
    private void validateDetailQuery(IpProxyStatsDetailQuery query) {
        if (query.getProtocol() != null) {
            ProxyProtocol.fromCode(query.getProtocol());
        }
        normalizeAllocationMode(query);
        if (query.getStatus() != null) {
            IpProxyStatus.fromCode(query.getStatus());
        }
    }

    /**
     * 将 Mapper 聚合计数行转换为前端展示行。
     *
     * @param row Mapper 聚合原始行
     * @return 带比率和风险标签的国家/地区统计行
     */
    private IpProxyCountryStatsVO toCountryVO(IpProxyCountryStatsRow row) {
        long total = nullToZero(row.getTotalIpCount());
        long idle = nullToZero(row.getIdleIpCount());
        long inUse = nullToZero(row.getInUseIpCount());
        long unavailable = nullToZero(row.getUnavailableIpCount());
        BigDecimal availableRate = rate(idle + inUse, total);
        BigDecimal unavailableRate = rate(unavailable, total);
        IpProxyResourceRisk risk = IpProxyResourceRisk.calculate(total, idle, inUse, availableRate, unavailableRate);
        return new IpProxyCountryStatsVO(
                row.getRegion(),
                total,
                inUse,
                idle,
                unavailable,
                availableRate,
                unavailableRate,
                row.getLastSampleCheckAt(),
                risk.value(),
                risk.label());
    }

    /**
     * 将 Mapper 明细原始行转换为前端展示行。
     *
     * @param row Mapper 明细原始行
     * @return 带枚举展示 label 的 IP 明细行
     */
    private IpProxyStatsDetailVO toDetailVO(IpProxyStatsDetailRow row) {
        return new IpProxyStatsDetailVO(
                row.getId(),
                row.getProxyHost(),
                row.getProxyPort(),
                row.getProxyAddress(),
                row.getProtocol(),
                ProxyProtocol.labelOf(row.getProtocol()),
                row.getRegion(),
                row.getStatus(),
                IpProxyStatus.labelOf(row.getStatus()),
                row.getBoundAccountId(),
                row.getSource(),
                row.getAllocationMode(),
                IpProxyAllocationMode.labelOf(row.getAllocationMode()),
                row.getOwnership(),
                ProxyOwnership.labelOf(row.getOwnership()),
                row.getLastSampleCheckAt(),
                row.getFailCount());
    }

    /**
     * 将数据库聚合可能返回的 null 计数转为 0。
     *
     * @param value 数据库计数
     * @return 非 null 计数
     */
    private static long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private static String toExportContent(List<IpProxy> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (IpProxy row : rows) {
            builder.append(nullToBlank(row.getHost()))
                    .append(':')
                    .append(row.getPort() == null ? "" : row.getPort())
                    .append(':')
                    .append(nullToBlank(row.getUsername()))
                    .append(':')
                    .append(nullToBlank(row.getPassword()))
                    .append('\n');
        }
        return builder.toString();
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private static String safeFilenamePart(String value) {
        String sanitized = value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_").trim();
        return StringUtils.hasText(sanitized) ? sanitized : "country";
    }

    private static void normalizeAllocationMode(IpProxyStatsCountryQuery query) {
        if (StringUtils.hasText(query.getAllocationMode())) {
            query.setAllocationMode(IpProxyAllocationMode.fromValue(query.getAllocationMode()).value());
        }
    }

    private static void normalizeAllocationMode(IpProxyStatsDetailQuery query) {
        if (StringUtils.hasText(query.getAllocationMode())) {
            query.setAllocationMode(IpProxyAllocationMode.fromValue(query.getAllocationMode()).value());
        }
    }

    /** 校验国家级抽样数量,先挡住无效入参,避免进入随机取样和真实代理检测。 */
    private static int validateSampleCount(IpProxyCountrySampleCheckDTO request) {
        Integer sampleCount = request == null ? null : request.sampleCount();
        if (sampleCount == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "抽样检测数量不能为空");
        }
        if (sampleCount <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "抽样检测数量必须大于 0");
        }
        return sampleCount;
    }

    private IpProxyCountrySampleStatsVO loadCountrySampleStats(String region) {
        IpProxyCountrySampleStatsVO stats = mapper.selectCountrySampleStatsByRegion(region);
        if (stats == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "国家不存在或未启用 IP 管理: " + region);
        }
        return stats;
    }

    private List<IpProxyCheckResultVO> checkSampleIds(List<Long> sampleIds) {
        List<IpProxyCheckResultVO> results = new ArrayList<>(sampleIds.size());
        for (int start = 0; start < sampleIds.size(); start += SAMPLE_CHECK_BATCH_SIZE) {
            int end = Math.min(start + SAMPLE_CHECK_BATCH_SIZE, sampleIds.size());
            results.addAll(ipProxyService.checkProxies(sampleIds.subList(start, end)));
        }
        return List.copyOf(results);
    }

    /**
     * 计算百分比并保留两位小数。
     *
     * @param numerator   分子
     * @param denominator 分母
     * @return 百分比数值;分母非正时返回 0.00
     */
    private static BigDecimal rate(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(RATE_SCALE);
        }
        return BigDecimal.valueOf(numerator)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(denominator), RATE_SCALE, RoundingMode.HALF_UP);
    }
}
