package com.armada.account.model.vo;

/**
 * Mapper 投影:账号导入原始格式导出所需字段。
 */
public class AccountImportExportRow {

    /** 明细主键。 */
    private Long id;

    /** 行号。 */
    private Integer lineNo;

    /** WA 账号号码。 */
    private String wsPhone;

    /** 解析结果:1成功入库 2重复 3格式错误 4凭据不全。 */
    private Integer parseResult;

    /** 单条原始导入内容。 */
    private String rawPayload;

    /** 原始条目名:zip 内路径或文本导入 line-N。 */
    private String sourceEntryName;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getLineNo() {
        return lineNo;
    }

    public void setLineNo(Integer lineNo) {
        this.lineNo = lineNo;
    }

    public String getWsPhone() {
        return wsPhone;
    }

    public void setWsPhone(String wsPhone) {
        this.wsPhone = wsPhone;
    }

    public Integer getParseResult() {
        return parseResult;
    }

    public void setParseResult(Integer parseResult) {
        this.parseResult = parseResult;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public void setRawPayload(String rawPayload) {
        this.rawPayload = rawPayload;
    }

    public String getSourceEntryName() {
        return sourceEntryName;
    }

    public void setSourceEntryName(String sourceEntryName) {
        this.sourceEntryName = sourceEntryName;
    }
}
