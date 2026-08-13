package com.armada.group.normalcreation.model.dto;

/** 创建新建普群任务请求。 */
public record NormalGroupCreationCreateDTO(
        Long adminAccountGroupId,
        Long secondaryAdminAccountGroupId,
        Integer secondaryAdminCount,
        String creatorLeavePolicy,
        String memberSource,
        Long memberAccountGroupId,
        Integer memberCount,
        Long folderId,
        String groupNameTemplate,
        Integer groupCount,
        Integer startNo,
        String speed,
        Long successMigrationGroupId,
        Long failedMigrationGroupId,
        NormalGroupCreationSettingsDTO settings) {
}
