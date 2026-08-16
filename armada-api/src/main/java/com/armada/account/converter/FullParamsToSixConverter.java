package com.armada.account.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把一条全参账号转换为 Android Zhuan 已验证的六段运行时凭据。
 *
 * <p>转换只保留 Android 上线需要的六个字段。签名预密钥、注册 ID、设备型号等原始字段
 * 由导入明细保存，不进入运行时凭据，也不得写入日志或错误消息。</p>
 */
public class FullParamsToSixConverter {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\d{7,15}$");

    private static final Map<String, String> FIELD_MAPPING = fieldMapping();

    /**
     * 校验并转换单条全参 JSON 对象。
     *
     * @param source 全参 JSON 对象
     * @return 成功时包含手机号和六段凭据；失败时只包含不泄露字段值的错误原因
     */
    public Result convert(JsonNode source) {
        if (source == null || !source.isObject()) {
            return Result.failure("全参必须为 JSON 对象");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String sourceField : FIELD_MAPPING.keySet()) {
            JsonNode value = source.get(sourceField);
            if (value == null || !value.isTextual() || value.asText().trim().isEmpty()) {
                return Result.failure("凭据不全:缺 " + sourceField);
            }
            values.put(sourceField, value.asText().trim());
        }

        String phone = values.get("phone");
        if (!PHONE_PATTERN.matcher(phone).matches()) {
            return Result.failure("phone 必须为 7 到 15 位纯数字");
        }
        JsonNode jidNode = source.get("jid");
        if (jidNode != null && (!jidNode.isTextual() || !phone.equals(jidNode.asText().trim()))) {
            return Result.failure("phone 与 jid 不一致");
        }

        ObjectNode credential = JsonNodeFactory.instance.objectNode();
        for (Map.Entry<String, String> mapping : FIELD_MAPPING.entrySet()) {
            credential.put(mapping.getValue(), values.get(mapping.getKey()));
        }
        return Result.success(phone, credential);
    }

    private static Map<String, String> fieldMapping() {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("phone", "phone");
        mapping.put("clientStaticPublicKey", "static_pub_key");
        mapping.put("clientStaticPrivateKey", "static_pri_key");
        mapping.put("identityPublicKey", "id_pub_key");
        mapping.put("identityPrivateKey", "id_pri_key");
        mapping.put("phoneUUID", "phone_id");
        return Map.copyOf(mapping);
    }

    /**
     * 单条全参转换结果。
     *
     * @param phone 成功时的规范化手机号，失败时为空串
     * @param credential 成功时的六段凭据，失败时为空对象
     * @param error 失败原因，成功时为空串
     */
    public record Result(String phone, ObjectNode credential, String error) {

        /**
         * 创建成功结果。
         *
         * @param phone 手机号
         * @param credential 六段凭据
         * @return 成功结果
         */
        public static Result success(String phone, ObjectNode credential) {
            return new Result(phone, credential, "");
        }

        /**
         * 创建失败结果。
         *
         * @param error 不含敏感字段值的失败原因
         * @return 失败结果
         */
        public static Result failure(String error) {
            return new Result("", JsonNodeFactory.instance.objectNode(), error);
        }

        /**
         * 判断转换是否成功。
         *
         * @return 错误原因为空时返回 true
         */
        public boolean isSuccess() {
            return error.isEmpty();
        }
    }
}
