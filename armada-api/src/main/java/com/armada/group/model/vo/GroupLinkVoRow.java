package com.armada.group.model.vo;

import java.time.LocalDateTime;

/**
 * Mapper 投影:group_link LEFT JOIN group_link_import_batch,用于分组下链接分页列表。
 * 普通类 + getter/setter,供 MyBatis resultType 直接映射(underscore-to-camelCase 自动转换)。
 * 时间字段为 LocalDateTime(UTC),由 Converter 转成 epoch 毫秒出参。
 */
public class GroupLinkVoRow {

    private Long id;
    /** {@code link_url} 列通过 SELECT {@code AS url} 映射到此字段(非 underscore 自动转换)。 */
    private String url;
    private String groupName;
    private String sourceFileName;
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public void setSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
