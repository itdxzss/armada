package com.armada.account.selection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 账号圈选条件。由归一化后的 camelCase 筛选 JSON 解析而来。
 *
 * <p><b>能力边界</b>：归一化白名单里的 {@code continent} / {@code onlineStatus} /
 * {@code platform} / {@code widType} / {@code errorCode} / {@code errorDesc} /
 * {@code retentionDays*} / {@code createdAt*} / {@code loggedIn*} 在 armada 没有可下推的列，
 * 本类<b>解析后直接丢弃</b>，不进 SQL。这是有意的能力边界：宁可少筛，也不能让调用方以为筛了。
 * 补列之后在这里加组件、在 {@code AccountFilterSelectionMapper.xml} 加条件即可。</p>
 *
 * <p>{@code groupInviteAllowed} 会被解析保留，但当前同样没有可下推的列，SQL 侧暂不使用。</p>
 *
 * @param countryIso2s 命中国家码；空表示不限
 * @param excludeCountryIso2s 排除国家码；空表示不排除
 * @param groupIds 命中分组 ID；空表示不限
 * @param channelIds 命中渠道 ID；空表示不限
 * @param protocolId 接入协议标识；null 表示不限
 * @param accountType 账号类型 1 个人 2 商业；null 表示不限
 * @param phone 号码前缀；null 表示不限
 * @param friendCountMin 双向好友数下界；null 表示不限
 * @param friendCountMax 双向好友数上界；null 表示不限
 * @param registerDaysMin 注册天数下界；null 表示不限
 * @param registerDaysMax 注册天数上界；null 表示不限
 * @param groupInviteAllowed 是否允许被拉群；null 表示不限
 */
public record AccountFilterCriteria(
        List<String> countryIso2s,
        List<String> excludeCountryIso2s,
        List<Long> groupIds,
        List<Long> channelIds,
        String protocolId,
        Integer accountType,
        String phone,
        Long friendCountMin,
        Long friendCountMax,
        Long registerDaysMin,
        Long registerDaysMax,
        Boolean groupInviteAllowed
) {

    /** 全部条件为空的「不限定」实例。 */
    public static final AccountFilterCriteria UNRESTRICTED = new AccountFilterCriteria(
            List.of(), List.of(), List.of(), List.of(),
            null, null, null, null, null, null, null, null);

    /** 组件全部经过防御性拷贝，实例不可变。 */
    public AccountFilterCriteria {
        countryIso2s = countryIso2s == null ? List.of() : List.copyOf(countryIso2s);
        excludeCountryIso2s =
                excludeCountryIso2s == null ? List.of() : List.copyOf(excludeCountryIso2s);
        groupIds = groupIds == null ? List.of() : List.copyOf(groupIds);
        channelIds = channelIds == null ? List.of() : List.copyOf(channelIds);
    }

    /**
     * 解析归一化后的筛选 JSON。
     *
     * @param normalizedJson 归一化 JSON；null、空串或非法 JSON 一律视为不限定
     * @param mapper JSON 解码器
     * @return 圈选条件
     */
    public static AccountFilterCriteria parse(String normalizedJson, ObjectMapper mapper) {
        if (normalizedJson == null || normalizedJson.isBlank()) {
            return UNRESTRICTED;
        }
        JsonNode root;
        try {
            root = mapper.readTree(normalizedJson);
        } catch (Exception ex) {
            return UNRESTRICTED;
        }
        if (root == null || !root.isObject()) {
            return UNRESTRICTED;
        }
        return new AccountFilterCriteria(
                textList(root, "countryIso2s"),
                textList(root, "excludeCountryIso2s"),
                longList(root, "groupIds"),
                longList(root, "channelIds"),
                text(root, "protocolId"),
                integer(root, "accountType"),
                text(root, "phone"),
                longValue(root, "friendCountMin"),
                longValue(root, "friendCountMax"),
                longValue(root, "registerDaysMin"),
                longValue(root, "registerDaysMax"),
                bool(root, "groupInviteAllowed"));
    }

    /**
     * 判断是否没有任何有效条件。
     *
     * <p>没有条件的语义是「全部有效账号」，不是「不圈号」。</p>
     *
     * @return 不限定时返回 true
     */
    public boolean isUnrestricted() {
        return countryIso2s.isEmpty()
                && excludeCountryIso2s.isEmpty()
                && groupIds.isEmpty()
                && channelIds.isEmpty()
                && protocolId == null
                && accountType == null
                && phone == null
                && friendCountMin == null
                && friendCountMax == null
                && registerDaysMin == null
                && registerDaysMax == null
                && groupInviteAllowed == null;
    }

    private static List<String> textList(JsonNode root, String key) {
        JsonNode node = root.get(key);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                values.add(item.asText());
            }
        }
        return List.copyOf(values);
    }

    private static List<Long> longList(JsonNode root, String key) {
        JsonNode node = root.get(key);
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Long> values = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (item != null && item.canConvertToLong()) {
                values.add(item.asLong());
            }
        }
        return List.copyOf(values);
    }

    private static String text(JsonNode root, String key) {
        JsonNode node = root.get(key);
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static Integer integer(JsonNode root, String key) {
        JsonNode node = root.get(key);
        return node != null && node.canConvertToInt() ? node.asInt() : null;
    }

    private static Long longValue(JsonNode root, String key) {
        JsonNode node = root.get(key);
        return node != null && node.canConvertToLong() ? node.asLong() : null;
    }

    private static Boolean bool(JsonNode root, String key) {
        JsonNode node = root.get(key);
        return node != null && node.isBoolean() ? node.asBoolean() : null;
    }
}
