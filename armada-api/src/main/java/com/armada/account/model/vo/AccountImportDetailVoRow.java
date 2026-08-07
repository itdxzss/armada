package com.armada.account.model.vo;

/**
 * Mapper 投影:account_import_detail,用于明细分页列表。
 * 普通类 + getter/setter,供 MyBatis resultType 直接映射(underscore-to-camelCase 自动转换)。
 * 时间字段为 Long epoch 毫秒(UTC)。
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

    /** 首次上线结果:null=尚未结算;1成功 2失败 3密钥异常 4封号。 */
    private Integer loginResult;

    /** 导入上线阶段:0跳过 1待派发 2已派发待回写 3已结算。 */
    private Integer onlinePhase;

    /** 首次上线失败或异常原因。 */
    private String loginReason;

    /** 当前账号状态:1新增 2正常 3封禁 4导出 5解绑 6被抢登 7抢登中 8账号受限。 */
    private Integer accountState;

    /** 当前登录状态:1在线 2离线 3待上线。 */
    private Integer loginState;

    /** 当前账号状态原因,目前来自 account_state.block_reason。 */
    private String accountStateReason;

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

    public Integer getOnlinePhase() {
        return onlinePhase;
    }

    public void setOnlinePhase(Integer onlinePhase) {
        this.onlinePhase = onlinePhase;
    }

    public String getLoginReason() {
        return loginReason;
    }

    public void setLoginReason(String loginReason) {
        this.loginReason = loginReason;
    }

    public Integer getAccountState() {
        return accountState;
    }

    public void setAccountState(Integer accountState) {
        this.accountState = accountState;
    }

    public Integer getLoginState() {
        return loginState;
    }

    public void setLoginState(Integer loginState) {
        this.loginState = loginState;
    }

    public String getAccountStateReason() {
        return accountStateReason;
    }

    public void setAccountStateReason(String accountStateReason) {
        this.accountStateReason = accountStateReason;
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
