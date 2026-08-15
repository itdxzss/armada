package com.armada.group.model.dto;

/** 新群模型账号快照批量持久化使用的最小行模型。 */
public final class AccountGroupCurrentSnapshotRows {

    private AccountGroupCurrentSnapshotRows() {
    }

    /** 账号及旧 baseline 上下文；目标 sync state 只用于保留已证明的空 baseline。 */
    public record Context(
            Long accountId,
            String wsPhone,
            Integer baselineState,
            String baselineGroupJidsJson,
            String baselineGroupSubjectsJson,
            Integer baselineGroupCount,
            Long baselineCapturedAt,
            Long lastSyncRequestedAt,
            Integer targetBaselineCompleteness) {
    }

    /** 写前普通一致性读得到的已有 G/self-M/B 状态。 */
    public record Existing(
            String groupJid,
            Long groupId,
            Long participantId,
            Integer presenceStatus,
            String presenceSource,
            Long presenceObservedAt,
            Long bindingId,
            Long deletedAt) {
    }

    /** 群 JID 到新模型群主键。 */
    public record GroupId(String groupJid, Long groupId) {
    }

    /** 精确进退群事实写入成员当前状态时使用的行模型。 */
    public record ParticipantPresenceWrite(
            Long groupId,
            String groupJid,
            String pnJid,
            String lidJid,
            String phone,
            Integer presenceStatus,
            String presenceSource,
            String eventId,
            long occurredAt,
            long now,
            Long lastJoinedAt,
            String lastExitType,
            Long lastExitedAt,
            String lastExitSourceType,
            Integer role,
            String roleSource,
            Long roleObservedAt,
            String roleEventId,
            String lastSnapshotVersion,
            Integer wasInInitialBaseline,
            String baselineSubjectSnapshot,
            Long membershipActiveSinceAt,
            Long firstPostControlObservedAt) {

        public ParticipantPresenceWrite withGroupId(Long resolvedGroupId) {
            return new ParticipantPresenceWrite(
                    resolvedGroupId, groupJid, pnJid, lidJid, phone, presenceStatus,
                    presenceSource, eventId, occurredAt, now, lastJoinedAt,
                    lastExitType, lastExitedAt, lastExitSourceType,
                    role, roleSource, roleObservedAt, roleEventId, lastSnapshotVersion,
                    wasInInitialBaseline,
                    baselineSubjectSnapshot, membershipActiveSinceAt,
                    firstPostControlObservedAt);
        }
    }

    /** 单个可见群在 G/P/self-M/B 四域中的批量写入值。 */
    public record Write(
            Long groupId,
            String groupJid,
            String displayName,
            String avatarUrl,
            String subject,
            Integer memberCount,
            Long waCreatedAt,
            Boolean announceOnly,
            String pnJid,
            String phone,
            Integer role,
            String eventId,
            long syncAt,
            long now,
            Integer wasInInitialBaseline,
            String baselineSubjectSnapshot,
            Long membershipActiveSinceAt,
            Long firstPostControlObservedAt) {

        public Write withGroupId(Long resolvedGroupId) {
            return new Write(
                    resolvedGroupId, groupJid, displayName, avatarUrl,
                    subject, memberCount, waCreatedAt, announceOnly, pnJid, phone,
                    role, eventId, syncAt, now, wasInInitialBaseline,
                    baselineSubjectSnapshot, membershipActiveSinceAt,
                    firstPostControlObservedAt);
        }
    }

    /** 单账号群报告及 baseline 水位。 */
    public record SyncStateWrite(
            Long accountId,
            Integer baselineState,
            Integer baselineCompleteness,
            Long baselineCapturedAt,
            Integer baselineGroupCount,
            Long lastSyncRequestedAt,
            long lastReportedAt,
            boolean snapshotComplete,
            Long lastCompleteAt,
            long now) {
    }
}
