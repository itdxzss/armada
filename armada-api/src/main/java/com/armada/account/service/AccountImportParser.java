package com.armada.account.service;

import com.armada.account.converter.FullParamsToSixConverter;
import com.armada.account.model.entity.ImportFormat;
import com.armada.account.model.entity.ParsedEntry;
import com.armada.account.model.enums.AccountDeviceOsCode;
import com.armada.account.model.enums.AccountTypeCode;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 账号导入文件格式解析器。
 *
 * <p>纯函数组件:无 DB 调用、无外部 I/O,所有操作在内存完成。
 * 对每条输入产出 {@link ParsedEntry},单条失败写 {@code parseError} 不抛,
 * 整个批次仍返回解析结果供导入循环(1.2.3)逐条处理。</p>
 *
 * <p>SIX 格式按 CSV 行解析为 Android 直登凭据 JSON,支持五列或六列输入;
 * 五列输入在解析边界生成 {@code phone_id},单条失败写 {@code parseError}。</p>
 */
@Component
public class AccountImportParser {

    private static final Logger log = LoggerFactory.getLogger(AccountImportParser.class);

    /**
     * 自建 ObjectMapper(不假设 Spring 上下文中有共享 Bean)。
     * 照 MarketingTemplateConverter 的 BUTTONS_JSON 写法。
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /** 全参到 Android 六段的纯转换器。 */
    private final FullParamsToSixConverter fullParamsConverter = new FullParamsToSixConverter();

    /**
     * Wheel 在用的 Baileys 裸 creds 必须包含的顶层字段集合。
     * 缺少其中任何一个即凭据不全,导入明细标记 parseError。
     */
    private static final Set<String> JSON_REQUIRED_CREDS_KEYS = Set.of(
            "registrationId",    // WA 注册 ID,用于协议层握手身份验证
            "noiseKey",          // Noise 协议密钥对,建立加密信道必需
            "signedIdentityKey", // 签名身份密钥对,端对端加密必需
            "signedPreKey"       // 签名预密钥,初次握手必需
    );

    /**
     * WA 手机号合法格式:纯数字,7~15 位(E.164 去掉 +)。
     * 用于 wid 提取后的合法性校验及 PARAMS 格式的必要字段检查。
     */
    private static final Pattern WID_PATTERN = Pattern.compile("^\\d{7,15}$");

    /** iOS 原生凭据中的主 JID。 */
    private static final Pattern IOS_JID_PATTERN =
            Pattern.compile("^(\\d{7,15})@s\\.whatsapp\\.net$");

    /** iOS 原生凭据中的 LID。 */
    private static final Pattern IOS_LID_PATTERN = Pattern.compile("^\\d+@lid$");

    /** iOS 原生凭据必须存在的普通文本字段。 */
    private static final List<String> IOS_NATIVE_REQUIRED_TEXT_KEYS = List.of(
            "phone", "jid", "platform", "device", "manufacturer",
            "osVersion", "osBuildNumber", "whatsappVersion", "phoneUUID",
            "mcc", "mnc", "country", "language");

    /** iOS 原生凭据必须存在且能按标准 Base64 解码的字段。 */
    private static final List<String> IOS_NATIVE_PUBLIC_KEY_FIELDS = List.of(
            "clientStaticPublicKey", "identityPublicKey", "signPreKeyPublicKey");

    /** iOS 原生私钥字段，解码后固定为 32 字节。 */
    private static final List<String> IOS_NATIVE_PRIVATE_KEY_FIELDS = List.of(
            "clientStaticPrivateKey", "identityPrivateKey", "signPreKeyPrivateKey");

    /** iOS 原生凭据必须存在且为正整数的字段。 */
    private static final List<String> IOS_NATIVE_REQUIRED_POSITIVE_ID_KEYS =
            List.of("registrationID", "signPreKeyID");

    /** 五段 Android 输入的列数(phone 至 id_pri_key)。 */
    private static final int FIVE_SEGMENT_COLUMN_COUNT = 5;

    /** 六段 Android 输入的列数(phone 至 phone_id)。 */
    private static final int SIX_SEGMENT_COLUMN_COUNT = 6;

    /**
     * 解析导入文件并返回逐条结果列表。
     *
     * <p>text 与 fileBytes 二选一:text 非空则优先用 text;否则解 fileBytes。
     * JSON 格式支持:.zip 压缩包(一号一文件)、单对象、数组。
     * PARAMS 格式支持:TXT/粘贴文本中每个非空行一个 JSON 对象。
     * SIX 格式支持:每行五列或六列 Android 凭据;
     * 五列为 {@code phone,staticPub,staticPri,identityPub,identityPri},
     * 六列追加 {@code phoneId}。</p>
     *
     * @param format    导入格式枚举
     * @param deviceOs  设备系统编码；PARAMS 用它区分 Android 转换和 iOS 原生保真
     * @param accountType 导入申报账号类型；iOS PARAMS 用它校验 ios/smb_ios
     * @param fileBytes 文件字节(可为 null)
     * @param text      文本内容(可为 null;非空时优先于 fileBytes)
     * @return 逐条解析结果,每条可能含 parseError
     * @throws BusinessException 未知格式时抛业务异常
     */
    public List<ParsedEntry> parse(ImportFormat format,
                                   Integer deviceOs,
                                   Integer accountType,
                                   byte[] fileBytes,
                                   String text) {
        if (format == ImportFormat.SIX) {
            return parseSix(fileBytes, text);
        }
        if (format == ImportFormat.JSON) {
            return parseJson(fileBytes, text);
        }
        if (format == ImportFormat.PARAMS) {
            if (Integer.valueOf(AccountDeviceOsCode.IOS).equals(deviceOs)) {
                return parseIosNativeParams(fileBytes, text, accountType);
            }
            return parseParams(fileBytes, text);
        }
        throw new BusinessException(ErrorCode.VALIDATION, "未知导入格式: " + format);
    }

    // ---- SIX 格式 ----

    /** 解析五段或六段 Android CSV 字段顺序,并统一产出六字段语义凭据。 */
    private List<ParsedEntry> parseSix(byte[] fileBytes, String text) {
        String src = (text != null && !text.isEmpty()) ? text
                : (fileBytes != null ? new String(fileBytes, StandardCharsets.UTF_8) : "");
        if (src.isBlank()) {
            return makeErrorEntry("", "输入内容为空");
        }
        String[] lines = src.split("\\R");
        List<ParsedEntry> result = new ArrayList<>(lines.length);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty()) {
                continue;
            }
            result.add(parseSixLine(line, i + 1));
        }
        if (result.isEmpty()) {
            return makeErrorEntry("", "输入内容为空");
        }
        return result;
    }

    private ParsedEntry parseSixLine(String line, int lineNo) {
        String source = "six-input[" + lineNo + "]";
        ParsedEntry entry = new ParsedEntry();
        entry.setRaw(source);
        entry.setRawPayload(line);
        entry.setSourceEntryName(source);

        String[] parts = line.split(",", -1);
        if (parts.length != FIVE_SEGMENT_COLUMN_COUNT
                && parts.length != SIX_SEGMENT_COLUMN_COUNT) {
            entry.setParseError(
                    "五/六段格式错误:应为5列或6列(phone,static_pub_key,static_pri_key,"
                            + "id_pub_key,id_pri_key[,phone_id])");
            return entry;
        }
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].trim();
        }
        String phone = parts[0];
        entry.setWid(phone);
        if (!WID_PATTERN.matcher(phone).matches()) {
            entry.setParseError("wid 不合法: " + phone);
            return entry;
        }
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                entry.setParseError("六段格式错误:第" + (i + 1) + "列为空");
                return entry;
            }
        }

        ObjectNode data = mapper.createObjectNode();
        data.put("phone", phone);
        data.put("static_pub_key", parts[1]);
        data.put("static_pri_key", parts[2]);
        data.put("id_pub_key", parts[3]);
        data.put("id_pri_key", parts[4]);
        String phoneId = parts.length == SIX_SEGMENT_COLUMN_COUNT
                ? parts[5]
                : UUID.randomUUID().toString().replace("-", "");
        data.put("phone_id", phoneId);
        entry.setData(data);
        return entry;
    }

    // ---- JSON 格式 ----

    /**
     * 解析 JSON 格式:优先 text,其次 fileBytes(支持 .zip 内存解压)。
     */
    private List<ParsedEntry> parseJson(byte[] fileBytes, String text) {
        if (text != null && !text.isEmpty()) {
            return parseJsonText(text, "text-input");
        }
        if (fileBytes == null || fileBytes.length == 0) {
            return makeErrorEntry("", "输入内容为空");
        }
        if (isZipBytes(fileBytes)) {
            return parseJsonZip(fileBytes);
        }
        return parseJsonText(new String(fileBytes, StandardCharsets.UTF_8), "file-input");
    }

    /**
     * 逐条解析 JSON 文本(单对象或数组)。
     */
    private List<ParsedEntry> parseJsonText(String text, String source) {
        try {
            JsonNode root = mapper.readTree(text);
            if (root.isArray()) {
                return parseJsonArray(root, source);
            }
            if (root.isObject()) {
                return List.of(parseJsonNode(root, source, text, source));
            }
            return List.of(makeErrorEntry(source, "JSON 格式不支持:既不是对象也不是数组", text));
        } catch (IOException e) {
            log.warn("[AccountImportParser] JSON 解析失败 source={} error={}", source, e.getMessage());
            return List.of(makeErrorEntry(source, "JSON 解析失败: " + e.getMessage(), text));
        }
    }

    /**
     * 逐条解析 JSON 数组。
     */
    private List<ParsedEntry> parseJsonArray(JsonNode array, String source) {
        List<ParsedEntry> result = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JsonNode node = array.get(i);
            String entryName = source + "[" + i + "]";
            result.add(parseJsonNode(node, entryName, compactJson(node), entryName));
        }
        return result;
    }

    /**
     * 解析单个 JSON 对象节点:抠 wid + 完整性校验。
     */
    private ParsedEntry parseJsonNode(JsonNode node, String source, String rawPayload, String sourceEntryName) {
        ParsedEntry entry = new ParsedEntry();
        // raw 只记来源标识,不记 creds 内容(日志脱敏)
        entry.setRaw(source);
        entry.setRawPayload(rawPayload);
        entry.setSourceEntryName(sourceEntryName);
        entry.setData(node);
        entry.setWid(extractWid(node));
        String credError = checkJsonCredCompleteness(node);
        if (credError != null) {
            entry.setParseError(credError);
        }
        return entry;
    }

    /**
     * 解压 .zip 并逐 .json 条目解析。
     */
    private List<ParsedEntry> parseJsonZip(byte[] zipBytes) {
        List<ParsedEntry> result = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry ze;
            while ((ze = zis.getNextEntry()) != null) {
                if (!ze.getName().endsWith(".json")) {
                    zis.closeEntry();
                    continue;
                }
                String entryName = ze.getName();
                byte[] content = zis.readAllBytes();
                zis.closeEntry();
                String entryText = new String(content, StandardCharsets.UTF_8);
                result.addAll(parseJsonText(entryText, entryName));
            }
        } catch (IOException e) {
            log.warn("[AccountImportParser] zip 解压失败 error={}", e.getMessage());
            return makeErrorEntry("zip-input", "zip 解压失败: " + e.getMessage());
        }
        return result;
    }

    /**
     * 校验 JSON creds 完整性:检查 {@link #JSON_REQUIRED_CREDS_KEYS} 中每个顶层键是否存在。
     *
     * @return null 表示完整;否则返回缺少第一个键的错误消息
     */
    private String checkJsonCredCompleteness(JsonNode node) {
        for (String key : JSON_REQUIRED_CREDS_KEYS) {
            if (!node.has(key)) {
                return "凭据不全:缺 " + key;
            }
        }
        return null;
    }

    // ---- PARAMS 格式 ----

    /**
     * 解析 PARAMS 格式:优先 text,其次 fileBytes;每个非空行独立解析和转换。
     */
    private List<ParsedEntry> parseParams(byte[] fileBytes, String text) {
        String src = (text != null && !text.isEmpty()) ? text
                : (fileBytes != null ? new String(fileBytes, StandardCharsets.UTF_8) : "");
        if (src.isBlank()) {
            return makeErrorEntry("", "输入内容为空");
        }
        String[] lines = src.split("\\R", -1);
        List<ParsedEntry> result = new ArrayList<>(lines.length);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            String source = "params-input[" + (i + 1) + "]";
            result.add(parseParamsLine(line, source, i + 1));
        }
        return result.isEmpty() ? makeErrorEntry("", "输入内容为空") : result;
    }

    private ParsedEntry parseParamsLine(String line, String source, int lineNo) {
        try {
            JsonNode node = mapper.readTree(line);
            if (!node.isObject()) {
                return makeErrorEntry(source, "全参必须为 JSON 对象", line);
            }
            return parseParamsNode(node, source, line);
        } catch (IOException e) {
            log.warn("[AccountImportParser] PARAMS JSON 解析失败 lineNo={}", lineNo);
            return makeErrorEntry(source, "JSON 解析失败", line);
        }
    }

    /**
     * 解析并转换单个全参对象，错误消息只包含字段名，不回显凭据值。
     */
    private ParsedEntry parseParamsNode(JsonNode node, String source, String rawPayload) {
        ParsedEntry entry = new ParsedEntry();
        entry.setRaw(source);
        entry.setRawPayload(rawPayload);
        entry.setSourceEntryName(source);
        String platform = node.path("platform").asText().trim();
        if ("ios".equals(platform) || "smb_ios".equals(platform)) {
            entry.setParseError("platform 与 deviceOs 不一致");
            return entry;
        }
        FullParamsToSixConverter.Result converted = fullParamsConverter.convert(node);
        if (!converted.isSuccess()) {
            entry.setParseError(converted.error());
            return entry;
        }
        entry.setWid(converted.phone());
        entry.setData(converted.credential());
        return entry;
    }

    /** 逐行解析并保留 iOS 原生凭据对象。 */
    private List<ParsedEntry> parseIosNativeParams(byte[] fileBytes, String text, Integer accountType) {
        String src = (text != null && !text.isEmpty()) ? text
                : (fileBytes != null ? new String(fileBytes, StandardCharsets.UTF_8) : "");
        if (src.isBlank()) {
            return makeErrorEntry("", "输入内容为空");
        }
        String[] lines = src.split("\\R", -1);
        List<ParsedEntry> result = new ArrayList<>(lines.length);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            String source = "params-input[" + (i + 1) + "]";
            try {
                JsonNode node = mapper.readTree(line);
                if (!node.isObject()) {
                    result.add(makeErrorEntry(source, "iOS 原生全参必须为 JSON 对象", line));
                    continue;
                }
                result.add(parseIosNativeNode(node, source, line, accountType));
            } catch (IOException exception) {
                log.warn("[AccountImportParser] iOS 原生 PARAMS JSON 解析失败 lineNo={}", i + 1);
                result.add(makeErrorEntry(source, "JSON 解析失败", line));
            }
        }
        return result.isEmpty() ? makeErrorEntry("", "输入内容为空") : result;
    }

    /** 校验 iOS 原生凭据；错误只包含字段名和规则，不回显字段值。 */
    private ParsedEntry parseIosNativeNode(
            JsonNode node, String source, String rawPayload, Integer accountType) {
        ParsedEntry entry = new ParsedEntry();
        entry.setRaw(source);
        entry.setRawPayload(rawPayload);
        entry.setSourceEntryName(source);

        String error = validateIosNativeCredential(node, accountType);
        if (error != null) {
            entry.setParseError(error);
            return entry;
        }
        entry.setWid(node.path("phone").asText().trim());
        entry.setData(node);
        return entry;
    }

    private String validateIosNativeCredential(JsonNode node, Integer accountType) {
        for (String key : IOS_NATIVE_REQUIRED_TEXT_KEYS) {
            if (!hasNonBlankText(node, key)) {
                return "凭据不全:缺 " + key;
            }
        }
        for (String key : IOS_NATIVE_PUBLIC_KEY_FIELDS) {
            String error = validateRequiredBase64Length(node, key, 32, 33);
            if (error != null) {
                return error;
            }
        }
        for (String key : IOS_NATIVE_PRIVATE_KEY_FIELDS) {
            String error = validateRequiredBase64Length(node, key, 32);
            if (error != null) {
                return error;
            }
        }
        String signatureError = validateRequiredBase64Length(node, "signPreKeySignature", 64);
        if (signatureError != null) {
            return signatureError;
        }
        String routingError = validateRequiredNonEmptyBase64(node, "edgeRoutingInfo");
        if (routingError != null) {
            return routingError;
        }
        for (String key : IOS_NATIVE_REQUIRED_POSITIVE_ID_KEYS) {
            JsonNode value = node.get(key);
            if (value == null || value.isNull()) {
                return "凭据不全:缺 " + key;
            }
            if (!value.canConvertToLong()
                    || value.asLong() <= 0
                    || value.asLong() > 0xFFFF_FFFFL) {
                return key + " 格式错误";
            }
        }

        String phone = node.path("phone").asText().trim();
        if (!WID_PATTERN.matcher(phone).matches()) {
            return "phone 格式错误";
        }
        java.util.regex.Matcher jidMatcher = IOS_JID_PATTERN.matcher(
                node.path("jid").asText().trim());
        if (!jidMatcher.matches() || !phone.equals(jidMatcher.group(1))) {
            return "phone 与 jid 不一致";
        }
        if (node.hasNonNull("lid")
                && !node.path("lid").asText().isBlank()
                && !IOS_LID_PATTERN.matcher(node.path("lid").asText().trim()).matches()) {
            return "lid 格式错误";
        }

        String platform = node.path("platform").asText().trim();
        String expectedPlatform;
        if (Integer.valueOf(AccountTypeCode.PERSONAL).equals(accountType)) {
            expectedPlatform = "ios";
        } else if (Integer.valueOf(AccountTypeCode.BUSINESS).equals(accountType)) {
            expectedPlatform = "smb_ios";
        } else {
            return "账号类型格式错误";
        }
        if (!expectedPlatform.equals(platform)) {
            return "platform 与账号类型不一致";
        }
        return null;
    }

    private boolean hasNonBlankText(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isTextual() && !value.asText().isBlank();
    }

    private String validateRequiredBase64Length(JsonNode node, String key, int... acceptedLengths) {
        if (!hasNonBlankText(node, key)) {
            return "凭据不全:缺 " + key;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(node.path(key).asText().trim());
        } catch (IllegalArgumentException exception) {
            return key + " 格式错误";
        }
        for (int acceptedLength : acceptedLengths) {
            if (decoded.length == acceptedLength) {
                return null;
            }
        }
        return key + " 长度错误";
    }

    private String validateRequiredNonEmptyBase64(JsonNode node, String key) {
        if (!hasNonBlankText(node, key)) {
            return "凭据不全:缺 " + key;
        }
        return validateNonEmptyBase64(node, key);
    }

    private String validateNonEmptyBase64(JsonNode node, String key) {
        try {
            return Base64.getDecoder().decode(node.path(key).asText().trim()).length > 0
                    ? null
                    : key + " 格式错误";
        } catch (IllegalArgumentException exception) {
            return key + " 格式错误";
        }
    }

    // ---- wid 抠取(通用) ----

    /**
     * 从 JSON 节点抠取 wid。优先级:
     * <ol>
     *   <li>顶层 {@code wid} 字段(纯数字)</li>
     *   <li>顶层 {@code phone} 字段(纯数字)</li>
     *   <li>顶层 {@code Phone} 字段(纯数字;兼容 wheel 在用 Baileys 文档)</li>
     *   <li>顶层 {@code me.id}(取 {@code :} 或 {@code @} 前的数字段)</li>
     * </ol>
     *
     * @return 提取的 wid 字符串,或 null 若均无法识别
     */
    private String extractWid(JsonNode node) {
        String wid = extractNumericText(node, "wid");
        if (wid != null) {
            return wid;
        }
        wid = extractNumericText(node, "phone");
        if (wid != null) {
            return wid;
        }
        wid = extractNumericText(node, "Phone");
        if (wid != null) {
            return wid;
        }
        // 顶层 me.id
        return extractMeId(node);
    }

    /**
     * 从节点的指定字段取纯数字文本(合法 wid 格式);不合法则返回 null。
     */
    private String extractNumericText(JsonNode node, String field) {
        JsonNode f = node.get(field);
        if (f == null || f.isNull()) {
            return null;
        }
        String val = f.asText().trim();
        return WID_PATTERN.matcher(val).matches() ? val : null;
    }

    /**
     * 从 {@code me.id} 字段取 {@code :} 或 {@code @} 前的数字段。
     * 例如 "8613800138000:7@s.whatsapp.net" → "8613800138000"。
     */
    private String extractMeId(JsonNode node) {
        JsonNode me = node.get("me");
        if (me == null || !me.isObject()) {
            return null;
        }
        JsonNode idNode = me.get("id");
        if (idNode == null || idNode.isNull()) {
            return null;
        }
        String raw = idNode.asText();
        // 取 ':' 或 '@' 前的部分
        int colonIdx = raw.indexOf(':');
        int atIdx = raw.indexOf('@');
        int cutIdx = -1;
        if (colonIdx >= 0 && atIdx >= 0) {
            cutIdx = Math.min(colonIdx, atIdx);
        } else if (colonIdx >= 0) {
            cutIdx = colonIdx;
        } else if (atIdx >= 0) {
            cutIdx = atIdx;
        }
        String candidate = (cutIdx >= 0) ? raw.substring(0, cutIdx) : raw;
        return WID_PATTERN.matcher(candidate.trim()).matches() ? candidate.trim() : null;
    }

    // ---- 工具 ----

    /** 判断字节数组是否为 ZIP 文件(Magic bytes: PK 0x50 0x4B)。 */
    private boolean isZipBytes(byte[] bytes) {
        return bytes.length >= 2 && bytes[0] == 0x50 && bytes[1] == 0x4B;
    }

    /** 将 JSON 节点序列化为紧凑文本,用于数组元素级导出。 */
    private String compactJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (IOException e) {
            return node == null ? "" : node.toString();
        }
    }

    /** 产出单条含错误的条目列表。 */
    private List<ParsedEntry> makeErrorEntry(String source, String error) {
        return Collections.singletonList(makeErrorEntry(source, error, source));
    }

    /** 产出单条含错误的条目,并保留可导出的原始文本。 */
    private ParsedEntry makeErrorEntry(String source, String error, String rawPayload) {
        ParsedEntry entry = new ParsedEntry();
        entry.setRaw(source);
        entry.setRawPayload(rawPayload);
        entry.setSourceEntryName(source);
        entry.setParseError(error);
        return entry;
    }
}
