package com.armada.platform.tenant.model.vo;

/**
 * 登录出参。{@code token} 为占位串(满足前端路由守卫),先测阶段非真鉴权凭据;前端后续以
 * {@code tenantCode} 作 X-Tenant-Code 头。
 */
public record TenantLoginVO(String tenantCode, String tenantName, String token) {}
