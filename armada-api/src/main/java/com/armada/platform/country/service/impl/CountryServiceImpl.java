package com.armada.platform.country.service.impl;

import com.armada.platform.country.mapper.CountryMapper;
import com.armada.platform.country.model.entity.Country;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.model.vo.CountryOptionsVO;
import com.armada.platform.country.service.CountryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 国家/地区主数据服务实现。
 *
 * <p>国家表是平台级主数据,不带 tenant_id。当前只承担 IP 管理下拉和旧 IP 代理池 region
 * 兼容转换两个职责:下拉对前端暴露稳定的 ISO2 值,写入/查询 IP 代理池时仍转换为历史使用的中文 region 快照。</p>
 */
@Service
public class CountryServiceImpl implements CountryService {

    /** 当前只开放给 IP 管理使用的选项范围。后续若有账号/群链接等差异化范围,在这里扩展 scope。 */
    private static final String IP_SCOPE = "ip";

    /** 前端选择“混合（不限国家）”时提交的稳定值。该值不是国家,不入 country 表。 */
    private static final String MIXED_VALUE = "MIXED";

    /** 兼容 ip_proxy.region 既有中文存储和分配优先级。 */
    private static final String MIXED_REGION = "混合（不限国家）";

    /** 下拉第一项虚拟选项,用于表达不限真实国家的混合代理池。 */
    private static final CountryOptionVO MIXED_OPTION =
            new CountryOptionVO(MIXED_VALUE, null, MIXED_REGION, "", "🌐", true);

    private final CountryMapper mapper;

    public CountryServiceImpl(CountryMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 查询国家下拉选项。
     *
     * <p>{@code scope} 为空时按 IP 管理处理;当前不支持其它范围,避免前端误用同一个接口表达不同业务语义。
     * 返回值固定把 {@code MIXED} 虚拟项放在第一位,真实国家只取启用且允许 IP 管理展示的主数据。</p>
     */
    @Override
    public CountryOptionsVO options(String scope) {
        String normalizedScope = StringUtils.hasText(scope) ? scope.trim() : IP_SCOPE;
        if (!IP_SCOPE.equalsIgnoreCase(normalizedScope)) {
            throw new BusinessException(ErrorCode.VALIDATION, "不支持的国家选项范围: " + scope);
        }
        List<CountryOptionVO> rows = new ArrayList<>();
        rows.add(MIXED_OPTION);
        for (Country country : mapper.selectIpSupported()) {
            rows.add(new CountryOptionVO(
                    country.getIso2(),
                    country.getIso2(),
                    country.getNameZh(),
                    country.getPhonePrefix() == null ? "" : country.getPhonePrefix(),
                    country.getFlag() == null ? "" : country.getFlag(),
                    false));
        }
        return new CountryOptionsVO(List.copyOf(rows));
    }

    /**
     * 把前端提交的国家值解析成 IP 代理池当前使用的中文 region。
     *
     * <p>新下拉提交真实国家 ISO2,旧页面/旧调用可能仍传中文名,所以解析顺序是:
     * MIXED 虚拟值 -> 二字母 ISO2 -> 中文展示名。找不到启用国家时抛业务校验异常,
     * 避免把未知值写入 {@code ip_proxy.region} 后影响后续分配优先级。</p>
     */
    @Override
    public String resolveIpRegion(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (MIXED_VALUE.equalsIgnoreCase(trimmed) || MIXED_REGION.equals(trimmed)) {
            return MIXED_REGION;
        }
        Country country = null;
        if (trimmed.length() == 2) {
            country = mapper.selectActiveByIso2(trimmed.toUpperCase(Locale.ROOT));
        }
        if (country == null) {
            country = mapper.selectActiveByNameZh(trimmed);
        }
        if (country == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "国家不存在或已停用: " + trimmed);
        }
        return country.getNameZh();
    }

    /**
     * 按账号手机号区号解析 IP 代理池中文 region。
     *
     * <p>国家主数据可能同时存在 {@code +1} 和 {@code +1-684} 这类前缀,
     * 因此必须用最长前缀优先,避免泛化区号抢占更具体地区。</p>
     */
    @Override
    public String resolveIpRegionByPhonePrefix(String wsPhone) {
        return resolveIpRegionByPhonePrefix(wsPhone, mapper.selectIpSupported());
    }

    /**
     * 批量按账号手机号区号解析 IP 代理池中文 region。
     *
     * <p>批量上线会一次处理多账号,这里集中读取国家主数据,避免 N 次重复查询。</p>
     */
    @Override
    public Map<String, String> resolveIpRegionsByPhonePrefix(Collection<String> wsPhones) {
        if (wsPhones == null || wsPhones.isEmpty()) {
            return Map.of();
        }
        List<Country> countries = mapper.selectIpSupported();
        Map<String, String> result = new LinkedHashMap<>();
        for (String wsPhone : wsPhones) {
            if (!result.containsKey(wsPhone)) {
                result.put(wsPhone, resolveIpRegionByPhonePrefix(wsPhone, countries));
            }
        }
        return result;
    }

    private static String resolveIpRegionByPhonePrefix(String wsPhone, List<Country> countries) {
        String phoneDigits = digitsOnly(wsPhone);
        if (!StringUtils.hasText(phoneDigits)) {
            return null;
        }
        String matchedRegion = null;
        int matchedPrefixLength = 0;
        for (Country country : countries) {
            String prefixDigits = digitsOnly(country.getPhonePrefix());
            if (!StringUtils.hasText(prefixDigits)) {
                continue;
            }
            if (prefixDigits.length() > matchedPrefixLength && phoneDigits.startsWith(prefixDigits)) {
                matchedRegion = country.getNameZh();
                matchedPrefixLength = prefixDigits.length();
            }
        }
        return matchedRegion;
    }

    /**
     * 按检测出的 ISO2 国家码解析成 IP 代理池中文 region。
     *
     * <p>检测结果只接受真实国家码,不接受 MIXED 虚拟项或中文名。这里复用 IP 管理下拉口径:
     * 只允许未删除、启用且支持 IP 管理的国家写入代理池。</p>
     */
    @Override
    public String resolveIpRegionByIso2(String iso2) {
        if (!StringUtils.hasText(iso2)) {
            throw new BusinessException(ErrorCode.VALIDATION, "检测国家码为空");
        }
        String normalized = iso2.trim().toUpperCase(Locale.ROOT);
        for (Country country : mapper.selectIpSupported()) {
            if (StringUtils.hasText(country.getIso2())
                    && normalized.equals(country.getIso2().trim().toUpperCase(Locale.ROOT))) {
                return country.getNameZh();
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "检测国家不支持 IP 管理: " + normalized);
    }

    private static String digitsOnly(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= '0' && ch <= '9') {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
