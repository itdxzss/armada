package com.armada.hyperlink.task.model.vo;

/** 后台准备恢复扫描的租户任务定位。 */
public record HyperlinkProvisionCandidate(long tenantId, long taskId) { }
