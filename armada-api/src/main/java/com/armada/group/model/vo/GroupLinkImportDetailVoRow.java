package com.armada.group.model.vo;

import java.time.LocalDateTime;

/**
 * Mapper 投影:group_link_import_detail JOIN group_link_import_batch,用于明细分页列表及失败导出。
 * 普通类 + getter/setter,供 MyBatis resultType 直接映射(underscore-to-camelCase 自动转换)。
 * 时间字段为 LocalDateTime(UTC),由 Converter 转成 epoch 毫秒出参。
 */
public class GroupLinkImportDetailVoRow {

    private int lineNo;
    private String groupName;
    private String rawUrl;
    private String sourceFileName;
    private int result;
    private String failReason;
    private LocalDateTime createdAt;

    public int getLineNo() {
        return lineNo;
    }

    public void setLineNo(int lineNo) {
        this.lineNo = lineNo;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getRawUrl() {
        return rawUrl;
    }

    public void setRawUrl(String rawUrl) {
        this.rawUrl = rawUrl;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public int getResult() {
        return result;
    }

    public void setResult(int result) {
        this.result = result;
    }

    public String getFailReason() {
        return failReason;
    }

    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
