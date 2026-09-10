package com.armada.marketing.model.dto;
import java.util.List;
/** 剧本定义仅编排角色和消息；管理员账号在创建任务时选择。 */
public record ScriptDefinitionSaveDTO(String name, Boolean enabled, List<ScriptMarketingStepDTO> steps) { }
