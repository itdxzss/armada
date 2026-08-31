package com.armada.hyperlink.task.model.entity;

/** 超链任务变更、导出和计费动作的持久审计事件。 */
public class HyperlinkTaskAuditEvent {
    private Long id;
    private Long tenantId;
    private String eventId;
    private String action;
    private Long actorUserId;
    private Long hyperlinkTaskId;
    private Long occurredAt;
    private Long createdAt;

    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { this.tenantId = value; }
    public String getEventId() { return eventId; }
    public void setEventId(String value) { this.eventId = value; }
    public String getAction() { return action; }
    public void setAction(String value) { this.action = value; }
    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long value) { this.actorUserId = value; }
    public Long getHyperlinkTaskId() { return hyperlinkTaskId; }
    public void setHyperlinkTaskId(Long value) { this.hyperlinkTaskId = value; }
    public Long getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Long value) { this.occurredAt = value; }
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long value) { this.createdAt = value; }
}
