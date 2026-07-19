package com.armada.marketing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.armada.marketing.model.enums.MarketingSendAttemptStatus;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MarketingGroupExecutionNormalizerTest {

    @Test
    void successAlwaysOverridesStalePrecheckStatus() {
        var result = MarketingGroupExecutionNormalizer.normalize(
                MarketingSendAttemptStatus.SUCCESS.code(), null, null, "BANNED", "CHAT_SUSPENDED");

        assertThat(result.groupStatus()).isEqualTo("NORMAL");
        assertThat(result.executionResult()).isEqualTo("SUCCESS");
        assertThat(result.executionReason()).isNull();
    }

    @ParameterizedTest
    @MethodSource("knownFailures")
    void failedAttemptUsesCanonicalStatusAndReason(String reasonCode,
                                                   String rawGroupStatus,
                                                   String groupStatusReason,
                                                   String expectedStatus,
                                                   String expectedReason) {
        var result = MarketingGroupExecutionNormalizer.normalize(
                MarketingSendAttemptStatus.FAILED.code(),
                reasonCode,
                "raw protocol message",
                rawGroupStatus,
                groupStatusReason);

        assertThat(result.groupStatus()).isEqualTo(expectedStatus);
        assertThat(result.executionResult()).isEqualTo("FAILED");
        assertThat(result.executionReason()).isEqualTo(expectedReason);
    }

    static Stream<Arguments> knownFailures() {
        return Stream.of(
                arguments("ACCOUNT_BANNED", "BANNED", "CHAT_SUSPENDED",
                        "ACCOUNT_BANNED", "账号封禁"),
                arguments("SEND_FAILED", "NO_PERMISSION", "ACCOUNT_NOT_PARTICIPANT",
                        "KICKED_OUT", "账号已被踢出群聊"),
                arguments("SEND_FAILED", "BANNED", "CHAT_TERMINATED",
                        "GROUP_BANNED", "群组已封禁"),
                arguments("SEND_FAILED", "NO_PERMISSION", "ANNOUNCE_ONLY_NON_ADMIN",
                        "NO_PERMISSION", "当前账号没有发言权限"),
                arguments("BANNED", "BANNED", null,
                        "GROUP_BANNED", "群组已封禁"));
    }

    @Test
    void unknownFailureKeepsSanitizedStoredReason() {
        var result = MarketingGroupExecutionNormalizer.normalize(
                MarketingSendAttemptStatus.FAILED.code(),
                "SEND_FAILED",
                " socket closed ",
                "UNCONFIRMED",
                "METADATA_QUERY_FAILED");

        assertThat(result.groupStatus()).isEqualTo("UNCONFIRMED");
        assertThat(result.executionResult()).isEqualTo("FAILED");
        assertThat(result.executionReason()).isEqualTo("socket closed");
    }

    @ParameterizedTest
    @MethodSource("ineffectiveStatuses")
    void submittedSkippedOrMissingAttemptHasNoExecution(Integer attemptStatus) {
        var result = MarketingGroupExecutionNormalizer.normalize(
                attemptStatus, "ACCOUNT_BANNED", "账号封禁", "BANNED", "CHAT_SUSPENDED");

        assertThat(result.groupStatus()).isEqualTo("UNCONFIRMED");
        assertThat(result.executionResult()).isNull();
        assertThat(result.executionReason()).isNull();
    }

    static Stream<Integer> ineffectiveStatuses() {
        return Stream.of(
                MarketingSendAttemptStatus.SUBMITTED.code(),
                MarketingSendAttemptStatus.SKIPPED.code(),
                null);
    }
}
