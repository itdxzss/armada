package com.armada.task.model.dto;
/** 仅包含业务开关，永不携带协议授权材料。 */
public record PullTaskCreatorDeletionConfigDTO(Boolean creatorDeleteAfterTakeover) { }
