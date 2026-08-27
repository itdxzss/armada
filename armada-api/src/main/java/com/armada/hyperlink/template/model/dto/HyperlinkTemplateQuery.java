package com.armada.hyperlink.template.model.dto;

import com.armada.shared.paging.PageQuery;

/** 超链模板列表筛选与分页参数。 */
public class HyperlinkTemplateQuery extends PageQuery {

    /** 模板名称模糊筛选。 */
    private String name;
    /** 消息类型筛选。 */
    private Integer messageType;
    /** 创建时间下界（毫秒时间戳，含）。 */
    private Long createdFrom;
    /** 创建时间上界（毫秒时间戳，含）。 */
    private Long createdTo;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getMessageType() {
        return messageType;
    }

    public void setMessageType(Integer messageType) {
        this.messageType = messageType;
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
}
