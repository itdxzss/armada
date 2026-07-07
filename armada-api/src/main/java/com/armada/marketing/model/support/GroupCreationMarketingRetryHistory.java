package com.armada.marketing.model.support;

import com.armada.marketing.model.entity.GroupCreationMarketingItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.util.StringUtils;

public class GroupCreationMarketingRetryHistory {

    private final List<Entry> entries;

    private GroupCreationMarketingRetryHistory(List<Entry> entries) {
        this.entries = List.copyOf(entries == null ? List.of() : entries);
    }

    public static GroupCreationMarketingRetryHistory empty() {
        return new GroupCreationMarketingRetryHistory(List.of());
    }

    public static GroupCreationMarketingRetryHistory parse(ObjectMapper objectMapper, String json) {
        if (!StringUtils.hasText(json)) {
            return empty();
        }
        try {
            Snapshot snapshot = objectMapper.readValue(json, Snapshot.class);
            return new GroupCreationMarketingRetryHistory(snapshot.entries());
        } catch (RuntimeException | JsonProcessingException ex) {
            return empty();
        }
    }

    public GroupCreationMarketingRetryHistory append(GroupCreationMarketingItem item,
                                                     String stage,
                                                     String reasonCode,
                                                     String reasonMessage,
                                                     long failedAt) {
        List<Entry> next = new ArrayList<>(entries);
        next.add(new Entry(
                item == null ? null : item.getAccountId(),
                item == null ? null : item.getAccountPhone(),
                item == null ? null : item.getProtocolAccountId(),
                stage,
                reasonCode,
                reasonMessage,
                failedAt));
        return new GroupCreationMarketingRetryHistory(next);
    }

    public GroupCreationMarketingRetryHistory withAttemptedAccountIds(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return this;
        }
        List<Entry> next = new ArrayList<>(entries);
        for (Long accountId : accountIds) {
            if (accountId != null && !containsAccountId(next, accountId)) {
                next.add(new Entry(accountId, null, null, "SEEDED", null, null, null));
            }
        }
        return new GroupCreationMarketingRetryHistory(next);
    }

    public List<Long> attemptedAccountIds() {
        Set<Long> ids = new LinkedHashSet<>();
        for (Entry entry : entries) {
            if (entry.accountId() != null) {
                ids.add(entry.accountId());
            }
        }
        return List.copyOf(ids);
    }

    public List<Entry> entries() {
        return entries;
    }

    public String toJson(ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(new Snapshot(entries));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("建群营销换号重试历史序列化失败", ex);
        }
    }

    private static boolean containsAccountId(List<Entry> entries, Long accountId) {
        for (Entry entry : entries) {
            if (accountId.equals(entry.accountId())) {
                return true;
            }
        }
        return false;
    }

    public record Entry(Long accountId,
                        String accountPhone,
                        String protocolAccountId,
                        String stage,
                        String reasonCode,
                        String reasonMessage,
                        Long failedAt) {
    }

    private record Snapshot(List<Entry> entries) {
        private Snapshot {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }
}
