package com.armada.account.model.vo;

/**
 * Mapper 投影:account_import_detail,用于明细分页列表及 CSV 导出。
 * 普通类 + getter/setter,供 MyBatis resultType 直接映射(underscore-to-camelCase 自动转换)。
 * 时间字段为 Long epoch 毫秒(UTC),导出 CSV 时转北京时间可读串。
 */
public class AccountImportDetailVoRow {

    /** 明细主键。 */
    private Long id;

    /** 行号。 */
    private int lineNo;

    /** WA 账号号码。 */
    private String wsPhone;

    /** 成功入库时关联的 account.id;失败为 null。 */
    private Long accountId;

    /** 解析结果:1成功入库 2重复 3格式错误 4凭据不全。 */
    private int parseResult;

    /** 解析结果中文标签(由 Service 层根据 parseResult 填充)。 */
    private String parseResultLabel;

    /** 失败原因;成功时为 null。 */
    private String failReason;

    /** 登录结果:null=未登录(step1);step3: 1成功 2失败 3密钥异常 4封号。 */
    private Integer loginResult;

    /** 创建时间(epoch 毫秒)。 */
    private Long createdAt;

    /** 所属分组名称(来自 account_import_batch JOIN account_group,供导出用)。 */
    private String groupName;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public int getLineNo() {
        return lineNo;
    }

    public void setLineNo(int lineNo) {
        this.lineNo = lineNo;
    }

    public String getWsPhone() {
        return wsPhone;
    }

    public void setWsPhone(String wsPhone) {
        this.wsPhone = wsPhone;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public int getParseResult() {
        return parseResult;
    }

    public void setParseResult(int parseResult) {
        this.parseResult = parseResult;
    }

    public String getParseResultLabel() {
        return parseResultLabel;
    }

    public void setParseResultLabel(String parseResultLabel) {
        this.parseResultLabel = parseResultLabel;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public Integer getLoginResult() {
        return loginResult;
    }

    public void setLoginResult(Integer loginResult) {
        this.loginResult = loginResult;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }
}
