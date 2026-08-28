package com.armada.contact.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 通讯录营销任务的账号筛选条件归一化器。
 *
 * <p>入库前按白名单收口：未知键丢弃、国家码大写、ID 去重、非正数下界剔除、空值剔除。
 * 白名单取自竞品任务页实际透传的键集（设计文档 §2.7）——注意 rotation_status 与
 * hyperlink_task_count 不在其中，通讯录任务不使用这两项。</p>
 *
 * <p>坏 JSON 归一为空对象而不是抛异常：筛选条件解析不了等价于「不限定」，
 * 让整个建任务请求因为一个筛选字段挂掉不划算。</p>
 */
@Component
public class ContactAccountFilterNormalizer {

    private static final Logger log = LoggerFactory.getLogger(ContactAccountFilterNormalizer.class);

    private static final String EMPTY_OBJECT = "{}";

    /** 大写归一的国家码数组字段。 */
    private static final List<String> COUNTRY_ARRAY_KEYS =
            List.of("country_iso2s", "exclude_country_iso2s");

    /** 正整数 ID 数组字段。 */
    private static final List<String> ID_ARRAY_KEYS = List.of("group_ids", "channel_ids");

    /** 直接透传的字符串字段。 */
    private static final List<String> TEXT_KEYS = List.of(
            "continent", "online_status", "account_type", "platform", "wid_type",
            "phone", "error_code", "error_desc", "protocol_id",
            "created_at_from", "created_at_to", "logged_in_from", "logged_in_to");

    /** 必须为正数才保留的范围字段。 */
    private static final List<String> POSITIVE_NUMBER_KEYS = List.of(
            "friend_count_min", "friend_count_max",
            "retention_days_min", "retention_days_max",
            "register_days_min", "register_days_max");

    /** 布尔字段。 */
    private static final List<String> BOOLEAN_KEYS = List.of("group_invite_allowed");

    private final ObjectMapper objectMapper;

    /**
     * 创建筛选归一化器。
     *
     * @param objectMapper JSON 编解码器
     */
    public ContactAccountFilterNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 把前端提交的筛选 JSON 归一为白名单内的 camelCase JSON。
     *
     * @param rawJson 原始 JSON 字符串，允许为 null 或非法
     * @return 归一后的 JSON 字符串，无有效条件时为空对象字面量
     */
    public String normalize(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return EMPTY_OBJECT;
        }
        JsonNode source;
        try {
            source = objectMapper.readTree(rawJson);
        } catch (Exception ex) {
            log.warn("账号筛选条件 JSON 解析失败,按不限定处理 errorType={}", ex.getClass().getSimpleName());
            return EMPTY_OBJECT;
        }
        if (source == null || !source.isObject()) {
            return EMPTY_OBJECT;
        }

        ObjectNode target = objectMapper.createObjectNode();
        for (String key : COUNTRY_ARRAY_KEYS) {
            Set<String> codes = new LinkedHashSet<>();
            for (JsonNode item : arrayOf(source, key)) {
                if (item != null && item.isTextual() && !item.asText().isBlank()) {
                    codes.add(item.asText().trim().toUpperCase(Locale.ROOT));
                }
            }
            if (!codes.isEmpty()) {
                target.putPOJO(camel(key), codes);
            }
        }
        for (String key : ID_ARRAY_KEYS) {
            Set<Long> ids = new LinkedHashSet<>();
            for (JsonNode item : arrayOf(source, key)) {
                if (item != null && item.canConvertToLong() && item.asLong() > 0) {
                    ids.add(item.asLong());
                }
            }
            if (!ids.isEmpty()) {
                target.putPOJO(camel(key), ids);
            }
        }
        for (String key : TEXT_KEYS) {
            JsonNode value = source.get(key);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                target.put(camel(key), value.asText().trim());
            }
        }
        for (String key : POSITIVE_NUMBER_KEYS) {
            JsonNode value = source.get(key);
            if (value != null && value.isNumber() && value.asLong() > 0) {
                target.put(camel(key), value.asLong());
            }
        }
        for (String key : BOOLEAN_KEYS) {
            JsonNode value = source.get(key);
            if (value != null && value.isBoolean()) {
                target.put(camel(key), value.asBoolean());
            }
        }
        return target.toString();
    }

    private static Iterable<JsonNode> arrayOf(JsonNode source, String key) {
        JsonNode value = source.get(key);
        return value != null && value.isArray() ? value : List.of();
    }

    /** snake_case 转 camelCase，落库字段统一按 armada 规范。 */
    private static String camel(String key) {
        StringBuilder builder = new StringBuilder(key.length());
        boolean upperNext = false;
        for (char ch : key.toCharArray()) {
            if (ch == '_') {
                upperNext = true;
                continue;
            }
            builder.append(upperNext ? Character.toUpperCase(ch) : ch);
            upperNext = false;
        }
        return builder.toString();
    }
}
