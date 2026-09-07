package com.armada.marketing.model.dto;

import java.util.List;

/** 剧本任务草稿，目标群与账号必须通过当前租户资源校验。 */
public record ScriptMarketingSaveDTO(
        String taskName,
        Integer intervalSeconds,
        Long startAt, Long endAt,
        List<Long> groupLinkIds,
        List<ScriptMarketingStepDTO> steps) { }
