package com.armada.marketing.model.support;

import com.armada.marketing.model.entity.GroupCreationMarketingItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GroupCreationMarketingRetryHistoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void appendsFailureAndExposesAttemptedAccountIds() {
        GroupCreationMarketingItem item = new GroupCreationMarketingItem();
        item.setAccountId(7L);
        item.setAccountPhone("8613000000000");
        item.setProtocolAccountId("acc_7");

        GroupCreationMarketingRetryHistory history = GroupCreationMarketingRetryHistory.empty()
                .append(item, "GROUP_CREATE", "GROUP_CREATE_FAILED", "rate-overlimit", 1000L);

        assertThat(history.attemptedAccountIds()).containsExactly(7L);
        assertThat(history.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.accountId()).isEqualTo(7L);
            assertThat(entry.accountPhone()).isEqualTo("8613000000000");
            assertThat(entry.protocolAccountId()).isEqualTo("acc_7");
            assertThat(entry.stage()).isEqualTo("GROUP_CREATE");
            assertThat(entry.reasonCode()).isEqualTo("GROUP_CREATE_FAILED");
            assertThat(entry.reasonMessage()).isEqualTo("rate-overlimit");
            assertThat(entry.failedAt()).isEqualTo(1000L);
        });
    }

    @Test
    void roundTripsJsonAndKeepsAttemptOrderWithoutDuplicateIds() {
        GroupCreationMarketingRetryHistory history = GroupCreationMarketingRetryHistory.empty()
                .append(item(7L, "acc_7"), "GROUP_CREATE", "GROUP_CREATE_FAILED", "rate-overlimit", 1000L)
                .append(item(9L, "acc_9"), "MARKETING_SEND", "MESSAGE_SEND_FAILED", "send failed", 2000L)
                .append(item(7L, "acc_7"), "ACCOUNT_CHECK", "ACCOUNT_OFFLINE", "offline", 3000L);

        String json = history.toJson(objectMapper);
        GroupCreationMarketingRetryHistory parsed = GroupCreationMarketingRetryHistory.parse(objectMapper, json);

        assertThat(parsed.entries()).hasSize(3);
        assertThat(parsed.attemptedAccountIds()).containsExactly(7L, 9L);
    }

    @Test
    void blankOrInvalidJsonIsTreatedAsEmpty() {
        assertThat(GroupCreationMarketingRetryHistory.parse(objectMapper, null).entries()).isEmpty();
        assertThat(GroupCreationMarketingRetryHistory.parse(objectMapper, " ").entries()).isEmpty();
        assertThat(GroupCreationMarketingRetryHistory.parse(objectMapper, "not-json").entries()).isEmpty();
    }

    @Test
    void attemptedAccountIdsCanBeSeededFromCurrentItem() {
        GroupCreationMarketingRetryHistory history = GroupCreationMarketingRetryHistory.parse(objectMapper, null)
                .withAttemptedAccountIds(List.of(7L, 9L, 7L));

        assertThat(history.attemptedAccountIds()).containsExactly(7L, 9L);
    }

    private static GroupCreationMarketingItem item(Long accountId, String protocolAccountId) {
        GroupCreationMarketingItem item = new GroupCreationMarketingItem();
        item.setAccountId(accountId);
        item.setAccountPhone("phone-" + accountId);
        item.setProtocolAccountId(protocolAccountId);
        return item;
    }
}
