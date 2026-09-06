package com.armada.boot.security;

import com.armada.account.model.dto.AccountImportDTO;
import com.armada.account.model.dto.DeviceImportDefaults;
import com.armada.account.model.entity.ImportFormat;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** 静态令牌映射；只保留令牌摘要，不将配置值附加到启动异常或日志。 */
public final class DeviceIngestTokens {

    /** 唯一运行环境输入；真实映射禁止进入源码或镜像。 */
    public static final String ENVIRONMENT_VARIABLE = "ARMADA_DEVICE_INGEST_CLIENTS_JSON";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43,256}");
    private static final Set<String> FIELDS = Set.of("token", "tenantId", "accountGroupId", "deviceOs",
            "accountType", "ipRegion", "ipAllocationMode");
    private static final int MAX_CONFIG_LENGTH = 262144;
    private static final int MAX_CLIENTS = 128;
    private static final int ANDROID = 1;
    private static final int APPLE = 2;
    private static final String SOURCE = "device-import";
    private final List<Entry> entries;

    /**
     * 解析运行环境映射；全部校验通过后才允许启动。
     * @param json 环境变量中的 JSON 数组
     * @throws IllegalStateException 缺失或无效配置；异常不携带原文与解析 cause
     */
    public DeviceIngestTokens(String json) {
        if (json == null || json.isBlank() || json.length() > MAX_CONFIG_LENGTH) {
            throw invalid("missing_or_oversized");
        }
        JsonNode root;
        try {
            root = new ObjectMapper().reader()
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
        } catch (IOException ex) {
            throw invalid("invalid_json");
        }
        if (root == null || !root.isArray() || root.isEmpty() || root.size() > MAX_CLIENTS) {
            throw invalid("invalid_array");
        }
        List<Entry> parsed = new ArrayList<>();
        for (JsonNode row : root) {
            Entry entry = parseEntry(row);
            if (parsed.stream().anyMatch(previous -> MessageDigest.isEqual(previous.digest(), entry.digest()))) {
                throw invalid("duplicate_token");
            }
            parsed.add(entry);
        }
        entries = List.copyOf(parsed);
    }

    /**
     * 严格匹配静态令牌；比较固定长度摘要，遍历全部条目后返回服务器默认值。
     * @param token 请求头令牌，未经 trim
     * @return 命中配置或空；不向调用方返回令牌或摘要
     */
    public Optional<DeviceImportDefaults> resolve(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        byte[] candidate = digest(token);
        DeviceImportDefaults matched = null;
        for (Entry entry : entries) {
            if (MessageDigest.isEqual(entry.digest(), candidate)) {
                matched = entry.defaults();
            }
        }
        return Optional.ofNullable(matched);
    }

    private Entry parseEntry(JsonNode row) {
        if (!row.isObject()) {
            throw invalid("invalid_entry");
        }
        row.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) {
                throw invalid("unknown_field");
            }
        });
        String token = text(row, "token");
        if (!TOKEN.matcher(token).matches()) {
            throw invalid("invalid_token_shape");
        }
        long tenantId = positiveLong(row, "tenantId");
        long groupId = positiveLong(row, "accountGroupId");
        int deviceOs = binaryChoice(row, "deviceOs");
        int accountType = binaryChoice(row, "accountType");
        String mode = optionalText(row, "ipAllocationMode");
        String region = optionalText(row, "ipRegion");
        if (!mode.isEmpty() && !Set.of("smart", "mixed").contains(mode)) {
            throw invalid("invalid_ip_mode");
        }
        if ((mode.isEmpty() && region.isEmpty()) || region.length() > 64) {
            throw invalid("invalid_ip_region");
        }
        AccountImportDTO metadata = new AccountImportDTO(groupId, ImportFormat.PARAMS.getCode(), deviceOs,
                accountType, region.isEmpty() ? null : region, mode.isEmpty() ? null : mode, null, SOURCE);
        return new Entry(digest(token), new DeviceImportDefaults(tenantId, metadata));
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalid("missing_or_invalid_" + field);
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode row, String field) {
        return row.path(field).isMissingNode() || row.path(field).isNull() ? "" : text(row, field);
    }

    private static long positiveLong(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw invalid("invalid_" + field);
        }
        return value.longValue();
    }

    private static int binaryChoice(JsonNode row, String field) {
        long value = positiveLong(row, field);
        if (value != ANDROID && value != APPLE) {
            throw invalid("invalid_" + field);
        }
        return (int) value;
    }

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw invalid("sha256_unavailable");
        }
    }

    private static IllegalStateException invalid(String code) {
        return new IllegalStateException(ENVIRONMENT_VARIABLE + " 配置不可用 code=" + code);
    }

    private record Entry(byte[] digest, DeviceImportDefaults defaults) {
    }
}
