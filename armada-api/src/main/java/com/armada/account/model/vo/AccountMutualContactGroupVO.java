package com.armada.account.model.vo;

/** 分组互存接口的vo，账号范围由服务端校验。 */
public record AccountMutualContactGroupVO(Long id, String name, long total, int eligible, long excluded) {}
