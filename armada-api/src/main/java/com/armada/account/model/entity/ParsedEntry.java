package com.armada.account.model.entity;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 单条账号导入解析结果。裸 POJO + getter/setter(无 Lombok)。
 *
 * <p>解析器 {@code AccountImportParser} 对每条输入产出一个 {@code ParsedEntry}。
 * 即使单条解析失败或凭据不完整,也会产出带 {@code parseError} 的条目,
 * 不抛异常——整个导入批次仍可继续,明细行展示具体原因。</p>
 *
 * <p>注意:SIX 格式是整体拒绝(抛 BusinessException),不走此条目路径。</p>
 */
public class ParsedEntry {

    /**
     * 原始字符串(JSON 文本或 CSV 行);用于日志/调试,不含敏感 creds 内容。
     * 仅记录来源标识(如文件名或截断后的首段),不直接打印完整 creds。
     */
    private String raw;

    /**
     * 从 JSON 中抠出的 WhatsApp ID(纯数字手机号段)。
     * 抠取路径优先级:顶层 {@code wid} → 顶层 {@code phone} → {@code creds.me.id} / {@code me.id}
     * (取 {@code :} 或 {@code @} 前的数字段)。
     * 若无法识别则为 {@code null}。
     */
    private String wid;

    /**
     * 原始 JSON 解析为树节点,供后续导入循环(1.2.3)按需读取字段。
     * SIX 格式无此字段。
     */
    private JsonNode data;

    /**
     * 解析/完整性校验错误原因。{@code null} 表示本条无错误。
     *
     * <p>场景:非法 JSON 格式("JSON 解析失败: ...")、凭据不全("凭据不全:缺 registrationId")、
     * wid 不合法("wid 不合法: xxx")等。</p>
     */
    private String parseError;

    public String getRaw() {
        return raw;
    }

    public void setRaw(String raw) {
        this.raw = raw;
    }

    public String getWid() {
        return wid;
    }

    public void setWid(String wid) {
        this.wid = wid;
    }

    public JsonNode getData() {
        return data;
    }

    public void setData(JsonNode data) {
        this.data = data;
    }

    public String getParseError() {
        return parseError;
    }

    public void setParseError(String parseError) {
        this.parseError = parseError;
    }
}
