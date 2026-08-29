package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 超链任务账号筛选唯一的白名单校验与归一化入口。 */
@Component
public class HyperlinkAccountFilterNormalizer {

    private static final Set<String> CONTINENTS = Set.of(
            "ASIA", "AFRICA", "EUROPE", "NORTH_AMERICA", "SOUTH_AMERICA", "OCEANIA",
            "ANTARCTICA");
    private static final Set<String> ONLINE_STATUSES = Set.of("ONLINE", "OFFLINE");
    private static final Set<String> PLATFORMS = Set.of(
            "ANDROID_PERSONAL", "ANDROID_BUSINESS_PRIMARY", "ANDROID_BUSINESS_COMPANION",
            "IOS_PERSONAL", "IOS_BUSINESS_PRIMARY", "IOS_BUSINESS_COMPANION");
    private static final Set<String> WID_TYPES = Set.of("web5", "native6");
    private static final Set<String> IMPORT_MODES = Set.of("six_segment", "full_param");

    /** 保存请求和运行时旧快照均调用本方法，禁止两套口径分叉。 */
    public HyperlinkAccountFilterDTO normalize(HyperlinkAccountFilterDTO value) {
        if (value == null || !Integer.valueOf(1).equals(value.filterSchemaVersion())) {
            throw validation("accountFilter.filterSchemaVersion 必须为 1");
        }
        List<String> countries = countryCodes(value.countryIso2s(), "countryIso2s");
        List<String> excludedCountries = countryCodes(
                value.excludeCountryIso2s(), "excludeCountryIso2s");
        if (countries.stream().anyMatch(excludedCountries::contains)) {
            throw validation("countryIso2s 与 excludeCountryIso2s 不能重复包含同一国家");
        }
        String continent = enumValue(value.continent(), CONTINENTS, true, "continent");
        String onlineStatus = enumValue(
                value.onlineStatus(), ONLINE_STATUSES, true, "onlineStatus");
        Integer rotationStatus = integerEnum(
                value.rotationStatus(), Set.of(0, 1, 2, 3), "rotationStatus");
        Integer accountType = integerEnum(value.accountType(), Set.of(1, 2), "accountType");
        String platform = enumValue(value.platform(), PLATFORMS, true, "platform");
        String widType = enumValue(value.widType(), WID_TYPES, false, "widType");
        String importMode = enumValue(value.importMode(), IMPORT_MODES, false, "importMode");
        Integer source = integerEnum(value.source(), Set.of(0, 1, 2, 3, 4), "source");
        Integer friendMin = integerBound(value.friendCountMin(), "friendCountMin");
        Integer friendMax = integerBound(value.friendCountMax(), "friendCountMax");
        validateRange(friendMin, friendMax, "friendCount");
        // 通讯录有名字联系人数：与 friendCount（双向好友）是两个口径，各自独立校验
        Integer contactNamedMin = integerBound(value.contactNamedNumMin(), "contactNamedNumMin");
        Integer contactNamedMax = integerBound(value.contactNamedNumMax(), "contactNamedNumMax");
        validateRange(contactNamedMin, contactNamedMax, "contactNamedNum");
        BigDecimal retentionMin = decimalBound(value.retentionDaysMin(), "retentionDaysMin");
        BigDecimal retentionMax = decimalBound(value.retentionDaysMax(), "retentionDaysMax");
        validateRange(retentionMin, retentionMax, "retentionDays");
        Integer registerMin = positiveIntegerBound(
                value.registerDaysMin(), "registerDaysMin");
        Integer registerMax = positiveIntegerBound(
                value.registerDaysMax(), "registerDaysMax");
        validateRange(registerMin, registerMax, "registerDays");
        Long createdFrom = epoch(value.createdAtFrom(), "createdAtFrom");
        Long createdTo = epoch(value.createdAtTo(), "createdAtTo");
        validateRange(createdFrom, createdTo, "createdAt");
        return new HyperlinkAccountFilterDTO(1, countries, excludedCountries, continent,
                ids(value.groupIds(), "groupIds"), ids(value.channelIds(), "channelIds"),
                upperText(value.protocolId(), 32, "protocolId"), onlineStatus, rotationStatus,
                accountType, platform, widType, importMode, value.groupInviteAllowed(),
                text(value.phone(), 32, "phone"),
                positiveId(value.importBatchId(), "importBatchId"), source,
                friendMin, friendMax, contactNamedMin, contactNamedMax,
                retentionMin, retentionMax, registerMin, registerMax,
                createdFrom, createdTo);
    }

    private List<String> countryCodes(List<String> values, String field) {
        if (values == null || values.isEmpty()) { return List.of(); }
        return List.copyOf(values.stream()
                .map(value -> upperText(value, 2, field))
                .peek(value -> {
                    if (value == null || !value.matches("[A-Z]{2}")) {
                        throw validation(field + " 必须是两位国家代码");
                    }
                })
                .distinct().sorted().toList());
    }

    private List<Long> ids(List<Long> values, String field) {
        if (values == null || values.isEmpty()) { return List.of(); }
        return values.stream().map(value -> requiredPositiveId(value, field))
                .distinct().sorted(Comparator.naturalOrder()).toList();
    }

    private Long requiredPositiveId(Long value, String field) {
        if (value == null || value < 1) { throw validation(field + " 元素必须大于 0"); }
        return value;
    }

    private Long positiveId(Long value, String field) {
        if (value != null && value < 1) { throw validation(field + " 必须大于 0"); }
        return value;
    }

    private Integer integerEnum(Integer value, Set<Integer> allowed, String field) {
        if (value != null && !allowed.contains(value)) { throw validation(field + " 非法"); }
        return value;
    }

    private String enumValue(String value, Set<String> allowed, boolean uppercase, String field) {
        String normalized = uppercase ? upperText(value, 64, field) : lowerText(value, 64, field);
        if (normalized != null && !allowed.contains(normalized)) { throw validation(field + " 非法"); }
        return normalized;
    }

    private Integer integerBound(Integer value, String field) {
        if (value == null || value == 0) { return null; }
        if (value < 0) { throw validation(field + " 不能为负数"); }
        return value;
    }

    private Integer positiveIntegerBound(Integer value, String field) {
        if (value != null && value < 1) { throw validation(field + " 必须大于 0"); }
        return value;
    }

    private BigDecimal decimalBound(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) == 0) { return null; }
        if (value.compareTo(BigDecimal.ZERO) < 0 || value.stripTrailingZeros().scale() > 1) {
            throw validation(field + " 必须非负且最多 0.1 天精度");
        }
        return value.stripTrailingZeros();
    }

    private Long epoch(Long value, String field) {
        if (value != null && value < 0) { throw validation(field + " 不能为负数"); }
        return value;
    }

    private <T extends Comparable<T>> void validateRange(T min, T max, String field) {
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw validation(field + " 最小值不能大于最大值");
        }
    }

    private String upperText(String value, int maxLength, String field) {
        String normalized = text(value, maxLength, field);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String lowerText(String value, int maxLength, String field) {
        String normalized = text(value, maxLength, field);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private String text(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) { return null; }
        String normalized = value.trim();
        if (normalized.length() > maxLength) { throw validation(field + " 长度超限"); }
        return normalized;
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
}
