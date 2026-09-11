package com.armada.marketing.model.vo;

import com.armada.marketing.model.dto.ScriptMarketingStepDTO;
import java.util.List;
import java.util.Map;

/** 固定配置、每群进度和发送记录。 */
public record ScriptMarketingDetailVO(ScriptMarketingTaskVO task,
        List<ScriptMarketingStepDTO> steps, List<ScriptMarketingGroupVO> groups,
        Map<Long, String> accountPhones) { }
