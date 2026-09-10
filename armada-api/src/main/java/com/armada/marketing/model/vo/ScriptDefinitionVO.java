package com.armada.marketing.model.vo;
import com.armada.marketing.model.dto.ScriptMarketingStepDTO;
import java.util.List;
/** 养群剧本完整定义，选入任务时复制为独立快照。 */
public record ScriptDefinitionVO(Long id, String name, Boolean enabled, Long updatedAt, List<ScriptMarketingStepDTO> steps) { }
