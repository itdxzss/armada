package com.armada.account.service;

import com.armada.account.model.entity.ImportFormat;
import com.armada.account.model.entity.ParsedEntry;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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
 * <p>SIX 格式是唯一整体拒绝路径:直接抛 {@link BusinessException},
 * 与单条 parseError 语义不同。</p>
 */
@Component
public class AccountImportParser {

    private static final Logger log = LoggerFactory.getLogger(AccountImportParser.class);

    /**
     * 自建 ObjectMapper(不假设 Spring 上下文中有共享 Bean)。
     * 照 MarketingTemplateConverter 的 BUTTONS_JSON 写法。
     */
    private final ObjectMapper mapper = new ObjectMapper();

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

    /**
     * 解析导入文件并返回逐条结果列表。
     *
     * <p>text 与 fileBytes 二选一:text 非空则优先用 text;否则解 fileBytes。
     * JSON 格式支持:.zip 压缩包(一号一文件)、单对象、数组。
     * PARAMS 格式支持:单对象、数组。
     * SIX 格式:整体拒绝,抛 {@link BusinessException}。</p>
     *
     * @param format    导入格式枚举
     * @param fileBytes 文件字节(可为 null)
     * @param text      文本内容(可为 null;非空时优先于 fileBytes)
     * @return 逐条解析结果,每条可能含 parseError
     * @throws BusinessException SIX 格式时整体拒绝
     */
    public List<ParsedEntry> parse(ImportFormat format, byte[] fileBytes, String text) {
        if (format == ImportFormat.SIX) {
            throw new BusinessException(ErrorCode.VALIDATION, "六段暂不支持(协议层未接)");
        }
        if (format == ImportFormat.JSON) {
            return parseJson(fileBytes, text);
        }
        if (format == ImportFormat.PARAMS) {
            return parseParams(fileBytes, text);
        }
        throw new BusinessException(ErrorCode.VALIDATION, "未知导入格式: " + format);
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
     * 解析 PARAMS 格式:优先 text,其次 fileBytes;支持单对象和数组。
     */
    private List<ParsedEntry> parseParams(byte[] fileBytes, String text) {
        String src = (text != null && !text.isEmpty()) ? text
                : (fileBytes != null ? new String(fileBytes, StandardCharsets.UTF_8) : "");
        if (src.isEmpty()) {
            return makeErrorEntry("", "输入内容为空");
        }
        try {
            JsonNode root = mapper.readTree(src);
            if (root.isArray()) {
                return parseParamsArray(root);
            }
            if (root.isObject()) {
                return List.of(parseParamsNode(root, "params-input[0]"));
            }
            return List.of(makeErrorEntry("params-input", "PARAMS 格式不支持:既不是对象也不是数组", src));
        } catch (IOException e) {
            log.warn("[AccountImportParser] PARAMS JSON 解析失败 error={}", e.getMessage());
            return List.of(makeErrorEntry("params-input", "JSON 解析失败: " + e.getMessage(), src));
        }
    }

    private List<ParsedEntry> parseParamsArray(JsonNode array) {
        List<ParsedEntry> result = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            result.add(parseParamsNode(array.get(i), "params-input[" + i + "]"));
        }
        return result;
    }

    /**
     * 解析单个 PARAMS 节点:校验 wid 存在且为合法手机号。
     */
    private ParsedEntry parseParamsNode(JsonNode node, String source) {
        ParsedEntry entry = new ParsedEntry();
        entry.setRaw(source);
        entry.setRawPayload(compactJson(node));
        entry.setSourceEntryName(source);
        entry.setData(node);
        JsonNode widNode = node.get("wid");
        if (widNode == null || widNode.isNull() || widNode.asText().isEmpty()) {
            entry.setParseError("wid 缺失");
            return entry;
        }
        String wid = widNode.asText();
        if (!WID_PATTERN.matcher(wid).matches()) {
            entry.setParseError("wid 不合法: " + wid);
            return entry;
        }
        entry.setWid(wid);
        return entry;
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
