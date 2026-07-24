package com.armada.admin.model.vo;

/** 当前用户接口返回的可信用户与租户信息。 */
public record CurrentAuthVO(AuthUserVO user, AuthTenantVO tenant) {
}
