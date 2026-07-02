package com.armada.account.model.dto;

import com.armada.shared.paging.PageQuery;

/**
 * 账号导入批次列表查询参数(可变 class extends PageQuery,供 @ModelAttribute 绑定)。
 *
 * <p>所有字段可选;非 null 时 SQL WHERE 追加对应条件(SQL 下推,禁内存分页)。</p>
 */
public class AccountImportQuery extends PageQuery {

    /** 精确匹配批次 ID(可选)。 */
    private Long id;

    /** 模糊匹配原始文件名(可选)。 */
    private String sourceFileName;

    /** 导入格式:1六段 2JSON 3全参(可选)。 */
    private Integer importFormat;

    /** 目标账号分组 ID(可选)。 */
    private Long accountGroupId;

    /** 机型:1安卓 2苹果(可选)。 */
    private Integer deviceOs;

    /** 账号类型:1个人 2商业(可选)。 */
    private Integer accountType;

    /** 批次状态:1进行中 2已完成(可选)。 */
    private Integer status;

    /** 登录结果:有失败/无失败(可选)。 */
    private String login;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public Integer getImportFormat() {
        return importFormat;
    }

    public void setImportFormat(Integer importFormat) {
        this.importFormat = importFormat;
    }

    public Long getAccountGroupId() {
        return accountGroupId;
    }

    public void setAccountGroupId(Long accountGroupId) {
        this.accountGroupId = accountGroupId;
    }

    public Integer getDeviceOs() {
        return deviceOs;
    }

    public void setDeviceOs(Integer deviceOs) {
        this.deviceOs = deviceOs;
    }

    public Integer getAccountType() {
        return accountType;
    }

    public void setAccountType(Integer accountType) {
        this.accountType = accountType;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }
}
