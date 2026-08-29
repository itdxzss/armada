package com.armada.hyperlink.task.model.dto;

import com.armada.shared.paging.PageQuery;

/** 超链任务收信人流水分页筛选。 */
public class HyperlinkRecipientQuery extends PageQuery {

    private Long taskId;
    private String phone;
    private String phoneLike;
    private String recipientCountryIso2;
    private String senderCountryIso2;
    private String failReason;
    private String sortField = "id";
    private String sortOrder = "asc";

    public HyperlinkRecipientQuery() {
        super.setPageSize(20);
    }

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getPhoneLike() { return phoneLike; }
    public void setPhoneLike(String phoneLike) { this.phoneLike = phoneLike; }
    public String getRecipientCountryIso2() { return recipientCountryIso2; }
    public void setRecipientCountryIso2(String value) { this.recipientCountryIso2 = value; }
    public String getSenderCountryIso2() { return senderCountryIso2; }
    public void setSenderCountryIso2(String value) { this.senderCountryIso2 = value; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public String getSortField() { return sortField; }
    public void setSortField(String sortField) { this.sortField = sortField; }
    public String getSortOrder() { return sortOrder; }
    public void setSortOrder(String sortOrder) { this.sortOrder = sortOrder; }
}
