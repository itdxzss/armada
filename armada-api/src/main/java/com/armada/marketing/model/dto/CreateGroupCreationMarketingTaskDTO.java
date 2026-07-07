package com.armada.marketing.model.dto;

import java.util.List;

public record CreateGroupCreationMarketingTaskDTO(
        String taskName,
        Long accountGroupId,
        String accountGroupName,
        Long marketingTemplateId,
        String marketingTemplateName,
        Integer sendIntervalSeconds,
        String groupNamePrefix,
        String remark,
        List<GroupCreationMarketingMaterialDTO> materials) {
}
