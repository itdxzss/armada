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
    private Long sourceAccountGroupId;
    private String groupJid;
    private Long pullerAccountGroupId;
    private Integer singleAddCount;
    private String idempotencyKey;

    /** @return 上传的拉群料子文件 */
    public MultipartFile getFile() { return file; }

    /** @param file 上传的拉群料子文件 */
    public void setFile(MultipartFile file) { this.file = file; }

    /** @return 来源历史群账号组 ID */
    public Long getSourceAccountGroupId() { return sourceAccountGroupId; }

    /** @param sourceAccountGroupId 来源历史群账号组 ID */
    public void setSourceAccountGroupId(Long sourceAccountGroupId) {
        this.sourceAccountGroupId = sourceAccountGroupId;
    }

    /** @return 目标群 JID */
    public String getGroupJid() { return groupJid; }

    /** @param groupJid 目标群 JID */
    public void setGroupJid(String groupJid) { this.groupJid = groupJid; }

    /** @return 拉手账号组 ID */
    public Long getPullerAccountGroupId() { return pullerAccountGroupId; }

    /** @param value 拉手账号组 ID */
    public void setPullerAccountGroupId(Long value) { this.pullerAccountGroupId = value; }

    /** @return 单次拉人数量 */
    public Integer getSingleAddCount() { return singleAddCount; }

    /** @param singleAddCount 单次拉人数量 */
    public void setSingleAddCount(Integer singleAddCount) { this.singleAddCount = singleAddCount; }

    /** @return 幂等键 */
    public String getIdempotencyKey() { return idempotencyKey; }

    /** @param idempotencyKey 幂等键 */
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    /**
     * 转换为 Service 不可变入参。
     *
     * @return 不包含上传文件的创建元数据
     */
    public HistoricalGroupPullCreateDTO toDTO() {
        return new HistoricalGroupPullCreateDTO(
                sourceAccountGroupId, groupJid, pullerAccountGroupId, singleAddCount, idempotencyKey);
    }
}
