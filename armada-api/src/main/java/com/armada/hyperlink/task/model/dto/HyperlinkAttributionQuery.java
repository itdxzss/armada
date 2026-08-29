package com.armada.hyperlink.task.model.dto;

/** 深度归因分页筛选。 */
public class HyperlinkAttributionQuery {
    private int page = 1;
    private int pageSize = 20;
    private String recipientPhone;
    private String senderPhone;
    private String sortField = "visitCount";
    private String sortOrder = "desc";

    public int getPage() { return Math.max(1, page); }
    public void setPage(int value) { this.page = value; }
    public int getPageSize() {
        return switch (pageSize) {
            case 10, 20, 50, 100, 200 -> pageSize;
            default -> 20;
        };
    }
    public void setPageSize(int value) { this.pageSize = value; }
    public String getRecipientPhone() { return normalized(recipientPhone); }
    public void setRecipientPhone(String value) { this.recipientPhone = value; }
    public String getSenderPhone() { return normalized(senderPhone); }
    public void setSenderPhone(String value) { this.senderPhone = value; }
    public String getSortField() { return "visitCount"; }
    public void setSortField(String value) { this.sortField = value; }
    public String getSortOrder() { return "asc".equalsIgnoreCase(sortOrder) ? "asc" : "desc"; }
    public void setSortOrder(String value) { this.sortOrder = value; }

    private String normalized(String value) {
        if (value == null || value.isBlank()) return null;
        String trimmed = value.trim();
        return trimmed.length() > 32 ? trimmed.substring(0, 32) : trimmed;
    }
}
