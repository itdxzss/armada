package com.armada.account.model.dto;

/** 同一身份别名的活跃剧本冻结材料，仅在服务端依赖复核使用。 */
public record CreatorActiveScriptBinding(Long accountId, String stepsJson, String bindingsJson) { }
