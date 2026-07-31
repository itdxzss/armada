package com.armada.platform.country.service.impl;

import com.armada.platform.country.mapper.CountryMapper;
import com.armada.platform.country.model.entity.Country;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.model.vo.CountryOptionsVO;
import com.armada.platform.country.model.vo.CountryReferenceVO;
import com.armada.platform.country.service.CountryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 国家/地区主数据服务实现。
 *
 * <p>国家表是平台级主数据,不带 tenant_id。下拉对前端暴露稳定的 ISO2/MIXED value；
 * IP 域继续兼容中文 region 快照，渠道域直接保存 value，既有 ID 引用方法保持不变。</p>
 */
@Service
public class CountryServiceImpl implements CountryService {

    /** 当前只开放给 IP 管理使用的选项范围。后续若有账号/群链接等差异化范围,在这里扩展 scope。 */
    private static final String IP_SCOPE = "ip";

    /** 前端选择“混合（不限国家）”时提交的稳定值。该值不是国家,不入 country 表。 */
    private static final String MIXED_VALUE = "MIXED";

    /** 兼容 ip_proxy.region 既有中文存储和分配优先级。 */
    private static final String MIXED_REGION = "混合（不限国家）";

    /** WhatsApp 明确手机号身份使用的用户 JID 后缀。 */
    private static final String USER_JID_SUFFIX = "@s.whatsapp.net";

    /** libphonenumber 解析带国际区号号码时使用的未知默认区域。 */
    private static final String UNKNOWN_REGION = "ZZ";

    /** Google 国际号码解析器,元数据由 libphonenumber 依赖提供。 */
    private static final PhoneNumberUtil PHONE_NUMBER_UTIL = PhoneNumberUtil.getInstance();

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

    /** {@inheritDoc} */
    @Override
    public Map<String, CountryReferenceVO> resolveActiveCountriesByPhoneNumbers(
            Collection<String> wsPhones) {
        if (wsPhones == null || wsPhones.isEmpty()) {
            return Map.of();
        }
        Map<String, Country> countriesByIso2 = mapper.selectActive().stream()
                .filter(country -> StringUtils.hasText(country.getIso2()))
                .collect(Collectors.toMap(
                        country -> country.getIso2().trim().toUpperCase(Locale.ROOT),
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        Map<String, CountryReferenceVO> result = new LinkedHashMap<>();
        for (String wsPhone : new LinkedHashSet<>(wsPhones)) {
            validRegionIso2(wsPhone)
                    .map(countriesByIso2::get)
                    .ifPresent(country -> result.put(wsPhone, toReference(country)));
        }
        return Collections.unmodifiableMap(result);
    }

    /** 严格校验国际号码并解析二字母区域码。 */
    private static Optional<String> validRegionIso2(String raw) {
        Optional<String> internationalPhone = internationalPhone(raw);
        if (internationalPhone.isEmpty()) {
            return Optional.empty();
        }
        try {
            Phonenumber.PhoneNumber parsed = PHONE_NUMBER_UTIL.parse(
                    internationalPhone.get(), UNKNOWN_REGION);
            if (!PHONE_NUMBER_UTIL.isValidNumber(parsed)) {
                return Optional.empty();
            }
            return Optional.ofNullable(PHONE_NUMBER_UTIL.getRegionCodeForNumber(parsed))
                    .filter(region -> region.length() == 2)
                    .map(region -> region.toUpperCase(Locale.ROOT));
        } catch (NumberParseException ignored) {
            return Optional.empty();
        }
    }

    /** 只接受纯数字国际号码、单个前导加号或明确的 WhatsApp PN JID。 */
    private static Optional<String> internationalPhone(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Optional.empty();
        }
        String normalized = raw.trim();
        if (normalized.endsWith(USER_JID_SUFFIX)) {
            normalized = normalized.substring(0, normalized.length() - USER_JID_SUFFIX.length());
        }
        if (normalized.contains("@")) {
            return Optional.empty();
        }
        if (normalized.startsWith("+")) {
            normalized = normalized.substring(1);
        }
        if (!StringUtils.hasText(normalized)
                || !normalized.chars().allMatch(Character::isDigit)) {
            return Optional.empty();
        }
        return Optional.of("+" + normalized);
    }

    private static String resolveIpRegionByPhonePrefix(String wsPhone, List<Country> countries) {
        Country matched = resolveCountryByPhonePrefix(wsPhone, countries);
        return matched == null ? null : matched.getNameZh();
    }

    private static Country resolveCountryByPhonePrefix(String wsPhone, List<Country> countries) {
        String phoneDigits = digitsOnly(wsPhone);
        if (!StringUtils.hasText(phoneDigits)) {
            return null;
        }
        Country matchedCountry = null;
        int matchedPrefixLength = 0;
        for (Country country : countries) {
            String prefixDigits = digitsOnly(country.getPhonePrefix());
            if (!StringUtils.hasText(prefixDigits)) {
                continue;
            }
            if (prefixDigits.length() > matchedPrefixLength && phoneDigits.startsWith(prefixDigits)) {
                matchedCountry = country;
                matchedPrefixLength = prefixDigits.length();
            }
        }
        return matchedCountry;
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

    /**
     * {@inheritDoc}
     *
     * <p>渠道只保存下拉选项的稳定 value。真实国家统一规范为大写 ISO2；MIXED
     * 是业务虚拟项，只能用于允许混合国家的字段。</p>
     */
    @Override
    public CountryOptionVO requireActiveOption(String value, boolean mixedAllowed) {
        String normalized = normalizeOptionValue(value);
        if (MIXED_VALUE.equals(normalized)) {
            if (!mixedAllowed) {
                throw new BusinessException(ErrorCode.VALIDATION, "预选区号必须选择真实国家，不能选择 MIXED");
            }
            return MIXED_OPTION;
        }
        Country country = mapper.selectActiveByIso2(normalized);
        if (country == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "国家不存在或已停用: " + normalized);
        }
        return toOption(country);
    }

    /**
     * {@inheritDoc}
     *
     * <p>先规范化并去重 value，再按 ISO2 批量查询；MIXED 直接使用内存中的虚拟选项。</p>
     */
    @Override
    public Map<String, CountryOptionVO> optionsByValues(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        LinkedHashSet<String> normalizedValues = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                normalizedValues.add(normalizeOptionValue(value));
            }
        }
        if (normalizedValues.isEmpty()) {
            return Map.of();
        }
        Map<String, CountryOptionVO> result = new LinkedHashMap<>();
        if (normalizedValues.remove(MIXED_VALUE)) {
            result.put(MIXED_VALUE, MIXED_OPTION);
        }
        if (!normalizedValues.isEmpty()) {
            for (Country country : mapper.selectByIso2s(List.copyOf(normalizedValues))) {
                CountryOptionVO option = toOption(country);
                // SQL 把有效记录排在前面；同 ISO2 存在历史软删行时保留第一条，避免覆盖当前主数据。
                result.putIfAbsent(option.value(), option);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** 把国家域内部实体转换为已有的下拉选项模型，跨业务域不暴露 Country 实体。 */
    private static CountryOptionVO toOption(Country country) {
        return new CountryOptionVO(
                country.getIso2(),
                country.getIso2(),
                country.getNameZh(),
                country.getPhonePrefix() == null ? "" : country.getPhonePrefix(),
                country.getFlag() == null ? "" : country.getFlag(),
                false);
    }

    /** 规范化并限制 CountryOptionVO.value，防止任意文本进入渠道国家字段。 */
    private static String normalizeOptionValue(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.VALIDATION, "国家不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (MIXED_VALUE.equals(normalized)) {
            return normalized;
        }
        if (normalized.length() != 2
                || normalized.charAt(0) < 'A' || normalized.charAt(0) > 'Z'
                || normalized.charAt(1) < 'A' || normalized.charAt(1) > 'Z') {
            throw new BusinessException(ErrorCode.VALIDATION, "国家值必须是 ISO2 或 MIXED: " + value.trim());
        }
        return normalized;
    }

    /** {@inheritDoc} */
    @Override
    public CountryReferenceVO requireActiveReference(Long countryId) {
        if (countryId == null || countryId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "国家ID必须为正整数");
        }
        Country country = mapper.selectActiveById(countryId);
        if (country == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "国家不存在或已停用: " + countryId);
        }
        return toReference(country);
    }

    /** {@inheritDoc} */
    @Override
    public Map<Long, CountryReferenceVO> referencesByIds(Collection<Long> countryIds) {
        if (countryIds == null || countryIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = countryIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return mapper.selectByIds(ids).stream()
                .map(CountryServiceImpl::toReference)
                .collect(Collectors.toUnmodifiableMap(CountryReferenceVO::id, Function.identity()));
    }

    /** 把国家实体转换为兼容既有 country.id 调用的只读引用。 */
    private static CountryReferenceVO toReference(Country country) {
        return new CountryReferenceVO(
                country.getId(),
                country.getIso2(),
                country.getNameZh(),
                country.getPhonePrefix(),
                country.getFlag());
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
