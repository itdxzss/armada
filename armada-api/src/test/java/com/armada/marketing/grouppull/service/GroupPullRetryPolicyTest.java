package com.armada.marketing.grouppull.service;

import com.armada.marketing.grouppull.model.enums.GroupPullSpeakPermission;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroupPullRetryPolicyTest {

    @Test
    void appliesConfiguredFriendRetriesAndFixedGroupRetries() {
        assertThat(GroupPullRetryPolicy.friendAttempts(3)).isEqualTo(4);
        assertThat(GroupPullRetryPolicy.friendAttempts(0)).isEqualTo(1);
        assertThat(GroupPullRetryPolicy.groupOperationAttempts()).isEqualTo(3);
    }

    @Test
    void recognizesOnlyConfirmedParticipantSuccess() {
        assertThat(GroupPullRetryPolicy.isParticipantSuccess(
                new GroupParticipantBatchResult.Item("a@s.whatsapp.net", "OK", null)))
                .isTrue();
        assertThat(GroupPullRetryPolicy.isParticipantSuccess(
                new GroupParticipantBatchResult.Item("a@s.whatsapp.net", "ALREADY_IN", null)))
                .isTrue();
        assertThat(GroupPullRetryPolicy.isParticipantSuccess(
                new GroupParticipantBatchResult.Item("a@s.whatsapp.net", "FAILED", "200")))
                .isTrue();
        assertThat(GroupPullRetryPolicy.isParticipantSuccess(
                new GroupParticipantBatchResult.Item("a@s.whatsapp.net", "FAILED", "403")))
                .isFalse();
    }

    @Test
    void requiresAdminOnlyForMutedOrBuilderExit() {
        assertThat(GroupPullRetryPolicy.adminRequired(
                GroupPullSpeakPermission.UNCHANGED, false)).isFalse();
        assertThat(GroupPullRetryPolicy.adminRequired(
                GroupPullSpeakPermission.UNMUTED, false)).isFalse();
        assertThat(GroupPullRetryPolicy.adminRequired(
                GroupPullSpeakPermission.MUTED, false)).isTrue();
        assertThat(GroupPullRetryPolicy.adminRequired(
                GroupPullSpeakPermission.UNCHANGED, true)).isTrue();
    }

    @Test
    void groupNameAlwaysKeepsSequenceSuffixWithinOneHundredCharacters() {
        String prefix = "很长的群名前缀".repeat(20);

        String name = GroupPullGroupNameGenerator.generate(prefix, 12345);

        assertThat(name).hasSize(100).endsWith("-12345");
    }
}
