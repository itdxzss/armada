package com.armada.group.normalcreation.model;

/** 新建普群 Mapper 使用的不可变数据行。 */
public final class NormalGroupCreationRecords {

    private NormalGroupCreationRecords() {
    }

    /** 任务插入行。 */
    public record TaskInsert(
            String idempotencyKey,
            Long adminAccountGroupId,
            Long memberAccountGroupId,
            int memberCount,
            int groupCount,
            String groupNameTemplate,
            int startNo,
            String creatorLeavePolicy,
            String speed,
            Long folderId,
            Long successMigrationGroupId,
            Long failedMigrationGroupId,
            boolean sendMessagesAllowed,
            boolean editGroupSettingsAllowed,
            boolean addMembersAllowed,
            boolean joinApprovalEnabled,
            int ephemeralDurationSeconds,
            long createdBy,
            long now) {
    }

    /** 计划群插入行。 */
    public record ItemInsert(
            Long taskId,
            int itemNo,
            String groupSubject,
            Long creatorAccountId,
            String creatorProtocolAccountId,
            String creatorProtocolBackend,
            String creatorWsPhone,
            long now) {
    }

    /** 批量插入后按任务序号回查的计划群主键。 */
    public record ItemIdentity(Long id, int itemNo) {
    }

    /** 计划群成员插入行。 */
    public record MemberInsert(
            Long taskId,
            Long itemId,
            int memberOrder,
            Long memberAccountId,
            String memberProtocolAccountId,
            String memberProtocolBackend,
            String memberWsPhone,
            long now) {
    }

    /** 执行一个阶段所需的任务和计划群冻结事实。 */
    public record ItemWork(
            Long id,
            Long tenantId,
            Long taskId,
            String groupSubject,
            String groupNameTemplate,
            Long creatorAccountId,
            String creatorProtocolAccountId,
            String creatorProtocolBackend,
            String creatorWsPhone,
            String groupJid,
            String status,
            String currentStep,
            String dispatchStatus,
            String createCommandId,
            String settingsCommandId,
            String leaveCommandId,
            String creatorLeavePolicy,
            Long folderId,
            Long successMigrationGroupId,
            Long failedMigrationGroupId,
            Boolean sendMessagesAllowed,
            Boolean editGroupSettingsAllowed,
            Boolean addMembersAllowed,
            Boolean joinApprovalEnabled,
            Integer ephemeralDurationSeconds) {
    }

    /** 联系人准备重试时替换成员执行账号。 */
    public record MemberReplacement(
            Long memberId,
            Long itemId,
            Long expectedMemberAccountId,
            Long memberAccountId,
            String memberProtocolAccountId,
            String memberProtocolBackend,
            String memberWsPhone,
            long now) {
    }

    /** 执行时使用的成员冻结事实。 */
    public record MemberWork(
            Long id,
            Long memberAccountId,
            String memberProtocolAccountId,
            String memberProtocolBackend,
            String memberWsPhone,
            String creatorSavedMemberStatus,
            String memberSavedCreatorStatus,
            String creatorSaveCommandId,
            String memberSaveCommandId,
            String participantStatus) {
    }

    /** 待补偿发布的小页。 */
    public record DispatchWork(
            Long tenantId,
            Long taskId,
            Long itemId,
            Long creatorAccountId,
            String dispatchStage) {
    }
}
