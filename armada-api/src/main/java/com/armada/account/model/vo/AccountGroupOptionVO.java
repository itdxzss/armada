package com.armada.account.model.vo;

/** 当前租户活跃账号分组及未删除账号总数，包含零账号分组。 */
public record AccountGroupOptionVO(Long id, String name, long accountCount) { }
