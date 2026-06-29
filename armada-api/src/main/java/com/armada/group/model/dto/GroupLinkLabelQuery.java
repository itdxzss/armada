package com.armada.group.model.dto;

import com.armada.shared.paging.PageQuery;

/**
 * WS链接分组列表查询参数(可变 class extends PageQuery,供 @ModelAttribute 绑定)。
 */
public class GroupLinkLabelQuery extends PageQuery {

    /** 关键字模糊搜索(匹配分组名称)。 */
    private String keyword;

    /** 精确匹配分组 ID(可选)。 */
    private Long id;

    /** 创建时间范围起(epoch毫秒)。 */
    private Long createdFrom;

    /** 创建时间范围止(epoch毫秒)。 */
    private Long createdTo;

    /** 导入时间范围起(epoch毫秒,匹配该分组下导入批次 created_at)。 */
    private Long importedFrom;

    /** 导入时间范围止(epoch毫秒,匹配该分组下导入批次 created_at)。 */
    private Long importedTo;

    /** 分组维度导入状态:EMPTY/DONE/PARTIAL/FAILED。 */
    private String status;

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCreatedFrom() {
        return createdFrom;
    }

    public void setCreatedFrom(Long createdFrom) {
        this.createdFrom = createdFrom;
    }

    public Long getCreatedTo() {
        return createdTo;
    }

    public void setCreatedTo(Long createdTo) {
        this.createdTo = createdTo;
    }

    public Long getImportedFrom() {
        return importedFrom;
    }

    public void setImportedFrom(Long importedFrom) {
        this.importedFrom = importedFrom;
    }

    public Long getImportedTo() {
        return importedTo;
    }

    public void setImportedTo(Long importedTo) {
        this.importedTo = importedTo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isStatusEmpty() {
        return statusEquals("EMPTY");
    }

    public boolean isStatusDone() {
        return statusEquals("DONE");
    }

    public boolean isStatusPartial() {
        return statusEquals("PARTIAL");
    }

    public boolean isStatusFailed() {
        return statusEquals("FAILED");
    }

    private boolean statusEquals(String expected) {
        return status != null && expected.equalsIgnoreCase(status.trim());
    }
}
