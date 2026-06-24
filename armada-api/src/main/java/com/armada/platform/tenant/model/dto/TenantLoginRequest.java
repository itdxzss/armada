package com.armada.platform.tenant.model.dto;

/** 租户登录入参。 */
public record TenantLoginRequest(String tenantCode, String password) {}
