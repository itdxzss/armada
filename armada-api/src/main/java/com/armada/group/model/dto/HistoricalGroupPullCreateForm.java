package com.armada.group.model.dto;

import org.springframework.web.multipart.MultipartFile;

/**
 * multipart 历史群拉人创建表单。
 *
 * <p>multipart 的 {@code @ModelAttribute} 通过 setter 绑定文件和字段，因此该边界对象保持可变；
 * 进入 Service 前转换为不可变 {@link HistoricalGroupPullCreateDTO}。</p>
 */
public class HistoricalGroupPullCreateForm {

    private MultipartFile file;
    private Long operationAccountId;
    private String groupJid;
    private Long pullerAccountGroupId;
    private Integer singleAddCount;
    private String idempotencyKey;

    public MultipartFile getFile() { return file; }
    public void setFile(MultipartFile file) { this.file = file; }
    public Long getOperationAccountId() { return operationAccountId; }
    public void setOperationAccountId(Long operationAccountId) { this.operationAccountId = operationAccountId; }
    public String getGroupJid() { return groupJid; }
    public void setGroupJid(String groupJid) { this.groupJid = groupJid; }
    public Long getPullerAccountGroupId() { return pullerAccountGroupId; }
    public void setPullerAccountGroupId(Long value) { this.pullerAccountGroupId = value; }
    public Integer getSingleAddCount() { return singleAddCount; }
    public void setSingleAddCount(Integer singleAddCount) { this.singleAddCount = singleAddCount; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    /**
     * 转换为 Service 不可变入参。
     *
     * @return 不包含上传文件的创建元数据
     */
    public HistoricalGroupPullCreateDTO toDTO() {
        return new HistoricalGroupPullCreateDTO(
                operationAccountId, groupJid, pullerAccountGroupId, singleAddCount, idempotencyKey);
    }
}
