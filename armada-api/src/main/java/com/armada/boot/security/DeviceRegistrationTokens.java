package com.armada.boot.security;

import com.armada.account.model.dto.DeviceRegistrationIdentity;
import com.armada.account.model.vo.CloudRegistrationDeviceVO;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 独立注册身份映射；普通设备使用令牌，自测免令牌设备须单独显式配置。 */
public final class DeviceRegistrationTokens {
    /** 唯一秘密配置入口，不放入 application.yml。 */
    public static final String ENVIRONMENT_VARIABLE = "ARMADA_DEVICE_REGISTRATION_CLIENTS_JSON";
    /** 仅自测环境显式配置的免令牌设备，默认关闭。 */
    public static final String TEST_ENVIRONMENT_VARIABLE = "ARMADA_DEVICE_REGISTRATION_TEST_CLIENTS_JSON";
    private static final Set<String> TEST_FIELDS = Set.of("tenantId", "deviceId");
    private final List<DeviceRegistrationIdentity> testDevices;
    private static final Set<String> FIELDS = Set.of("token", "tenantId", "deviceId", "cloudPhoneId", "displayName");
    private final List<Entry> entries;

    /** 默认空列表；启用时严格解析，任何异常均不包含原文。 */
    public DeviceRegistrationTokens(String input, String testInput) {
        try {
            if (input == null || input.length() > 65536) { throw invalid(); }
            JsonNode root = new ObjectMapper().reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(input);
            if (root == null || !root.isArray() || root.size() > 128) { throw invalid(); }
            List<Entry> parsed = new ArrayList<>();
            for (JsonNode row : root) {
                Entry next = parse(row);
                if (parsed.stream().anyMatch(old -> MessageDigest.isEqual(old.digest(), next.digest())
                        || (old.permit().tenantId() == next.permit().tenantId()
                        && old.permit().deviceId().equals(next.permit().deviceId()))
                        || (next.cloudDevice().isPresent() && old.cloudDevice().isPresent()
                        && old.cloudDevice().get().cloudPhoneId().equals(next.cloudDevice().get().cloudPhoneId())))) {
                    throw invalid();
                }
                parsed.add(next);
            }
            entries = List.copyOf(parsed);
            testDevices = parseTestDevices(testInput);
        } catch (Exception exception) { throw invalid(); }
    }

    /** 仅返回已绑定的本租户云手机；不返回秘密、普通手机或免令牌测试身份。 */
    public List<CloudRegistrationDeviceVO> cloudDevices(Long tenantId) {
        if (tenantId == null || tenantId <= 0) { return List.of(); }
        return entries.stream().filter(entry -> entry.permit().tenantId() == tenantId)
                .flatMap(entry -> entry.cloudDevice().stream()).toList();
    }

    /** 正常设备校验令牌；显式配置的自测设备仅在完全不带令牌时按设备标识匹配。 */
    public Optional<DeviceRegistrationIdentity> resolve(String token, String deviceId) {
        if (token == null) {
            return testDevices.stream().filter(device -> device.deviceId().equals(deviceId)).findFirst();
        }
        if (!token.matches("[A-Za-z0-9_-]{43,256}")) { return Optional.empty(); }
        byte[] digest = digest(token);
        DeviceRegistrationIdentity found = null;
        for (var entry : entries) {
            if (MessageDigest.isEqual(digest, entry.digest()) && entry.permit().deviceId().equals(deviceId)) { found = entry.permit(); }
        }
        return Optional.ofNullable(found);
    }

    private List<DeviceRegistrationIdentity> parseTestDevices(String input) throws java.io.IOException {
        if (input == null || input.length() > 65536) { throw invalid(); }
        JsonNode root = new ObjectMapper().reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(input);
        if (root == null || !root.isArray() || root.size() > 128) { throw invalid(); }
        List<DeviceRegistrationIdentity> devices = new ArrayList<>();
        for (JsonNode row : root) {
            if (!row.isObject() || row.size() != TEST_FIELDS.size()) { throw invalid(); }
            row.fieldNames().forEachRemaining(name -> { if (!TEST_FIELDS.contains(name)) { throw invalid(); } });
            var device = new DeviceRegistrationIdentity(positive(row, "tenantId"), uuid(row, "deviceId"));
            if (devices.stream().anyMatch(old -> old.deviceId().equals(device.deviceId()))
                    || entries.stream().anyMatch(old -> old.permit().deviceId().equals(device.deviceId()))) {
                throw invalid();
            }
            devices.add(device);
        }
        return List.copyOf(devices);
    }

    private Entry parse(JsonNode row) {
        if (!row.isObject() || (row.size() != 3 && row.size() != FIELDS.size())) { throw invalid(); }
        row.fieldNames().forEachRemaining(name -> { if (!FIELDS.contains(name)) { throw invalid(); } });
        String token = row.path("token").asText("");
        if (!token.matches("[A-Za-z0-9_-]{43,256}")) { throw invalid(); }
        var permit = new DeviceRegistrationIdentity(positive(row, "tenantId"), uuid(row, "deviceId"));
        Optional<CloudRegistrationDeviceVO> cloud = Optional.empty();
        if (row.has("cloudPhoneId") || row.has("displayName")) {
            String phone = row.path("cloudPhoneId").asText("");
            String name = row.path("displayName").asText("");
            if (!row.path("cloudPhoneId").isTextual() || !phone.matches("[1-9][0-9]{0,18}")
                    || !row.path("displayName").isTextual() || name.isBlank() || name.length() > 80
                    || !name.equals(name.strip()) || name.codePoints().anyMatch(Character::isISOControl)) { throw invalid(); }
            cloud = Optional.of(new CloudRegistrationDeviceVO(permit.deviceId(), phone, name));
        }
        return new Entry(digest(token), permit, cloud);
    }
    private String uuid(JsonNode row, String field) {
        String value = row.path(field).asText("");
        if (!UUID.fromString(value).toString().equals(value)) { throw invalid(); }
        return value;
    }
    private long positive(JsonNode row, String field) {
        var value = row.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) { throw invalid(); }
        return value.longValue();
    }
    private static byte[] digest(String token) {
        try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException exception) { throw invalid(); }
    }
    private static IllegalStateException invalid() { return new IllegalStateException(ENVIRONMENT_VARIABLE + " 配置无效"); }
    private record Entry(byte[] digest, DeviceRegistrationIdentity permit, Optional<CloudRegistrationDeviceVO> cloudDevice) { }
}
