package com.armada.account.model.dto;

/**
 * 账号导入 multipart 表单入参（{@code @ModelAttribute} 绑定）。
 *
 * <p>承载导入的元信息字段；上传文件 {@code MultipartFile} 由 Controller 单独以 {@code @RequestParam} 接收,
 * 不放入本表单(避免 DTO 持有文件流)。</p>
 *
 * <p>用<b>可变 class + setter</b>(非 record):Spring {@code @ModelAttribute} 走 setter 绑定 form-data 字段,
 * 不认 record 构造器。字段名 camelCase,与前端 form-data 字段名一致。</p>
 */
public class AccountImportForm {

    /** 目标账号分组 ID;为空则导入落系统默认分组。 */
    private Long accountGroupId;
    /** 导入格式:1=六段(暂不支持) 2=JSON 3=全参。 */
    private Integer importFormat;
    /** 机型:1=安卓 2=苹果;可空。 */
    private Integer deviceOs;
    /** 账号类型:1=个人 2=商业;导入即冻结。 */
    private Integer accountType;
    /** IP 国家/地区;可空。 */
    private String ipRegion;
    /** 批次名称。 */
    private String batchName;
    /** 备注;可空。 */
    private String remark;
    /** 文本粘贴内容(与上传文件二选一);可空。 */
    private String text;

    public Long getAccountGroupId() {
        return accountGroupId;
    }

    public void setAccountGroupId(Long accountGroupId) {
        this.accountGroupId = accountGroupId;
    }

    public Integer getImportFormat() {
        return importFormat;
    }

    public void setImportFormat(Integer importFormat) {
        this.importFormat = importFormat;
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

    public String getIpRegion() {
        return ipRegion;
    }

    public void setIpRegion(String ipRegion) {
        this.ipRegion = ipRegion;
    }

    public String getBatchName() {
        return batchName;
    }

    public void setBatchName(String batchName) {
        this.batchName = batchName;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
