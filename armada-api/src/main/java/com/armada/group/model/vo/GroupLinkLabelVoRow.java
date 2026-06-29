package com.armada.group.model.vo;

/**
 * Mapper 投影:group_link_label + 聚合链接数,用于列表分页。
 * 普通类 + getter/setter,供 MyBatis resultType 直接映射(underscore-to-camelCase 自动转换)。
 * 时间字段为 Long epoch 毫秒,由 GroupConverter 直映出参。
 */
public class GroupLinkLabelVoRow {

    private Long id;
    private String name;
    private String region;
    private String remark;
    private long linkCount;
    private long fileCount;
    private long totalRows;
    private long successRows;
    private long failedRows;
    private String latestSourceFile;
    private Long latestImportedAt;
    private String status;
    private Long createdAt;
    private Long updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public long getLinkCount() {
        return linkCount;
    }

    public void setLinkCount(long linkCount) {
        this.linkCount = linkCount;
    }

    public long getFileCount() {
        return fileCount;
    }

    public void setFileCount(long fileCount) {
        this.fileCount = fileCount;
    }

    public long getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(long totalRows) {
        this.totalRows = totalRows;
    }

    public long getSuccessRows() {
        return successRows;
    }

    public void setSuccessRows(long successRows) {
        this.successRows = successRows;
    }

    public long getFailedRows() {
        return failedRows;
    }

    public void setFailedRows(long failedRows) {
        this.failedRows = failedRows;
    }

    public String getLatestSourceFile() {
        return latestSourceFile;
    }

    public void setLatestSourceFile(String latestSourceFile) {
        this.latestSourceFile = latestSourceFile;
    }

    public Long getLatestImportedAt() {
        return latestImportedAt;
    }

    public void setLatestImportedAt(Long latestImportedAt) {
        this.latestImportedAt = latestImportedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
