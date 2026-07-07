package com.armada.marketing.service.impl;

import com.armada.marketing.mapper.GroupCreationMarketingTaskMapper;
import com.armada.marketing.model.entity.GroupCreationMarketingItem;
import com.armada.marketing.model.entity.GroupCreationMarketingTask;
import com.armada.marketing.model.enums.GroupCreationMarketingItemStatus;
import com.armada.marketing.model.support.GroupCreationMarketingRetryHistory;
import com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GroupCreationMarketingRetryService {

    public static final String STAGE_ACCOUNT_CHECK = "ACCOUNT_CHECK";
    public static final String STAGE_GROUP_CREATE = "GROUP_CREATE";
    public static final String STAGE_MARKETING_SEND = "MARKETING_SEND";
    public static final String REASON_NO_AVAILABLE_ACCOUNT = "NO_AVAILABLE_ACCOUNT";
    public static final String MESSAGE_NO_AVAILABLE_ACCOUNT = "没有可用账号";

    private final GroupCreationMarketingTaskMapper groupCreationMapper;
    private final ObjectMapper objectMapper;

    public GroupCreationMarketingRetryService(GroupCreationMarketingTaskMapper groupCreationMapper,
                                              ObjectMapper objectMapper) {
        this.groupCreationMapper = groupCreationMapper;
        this.objectMapper = objectMapper;
    }

    public boolean resetItemForAccountRetry(GroupCreationMarketingItem item,
                                            GroupCreationMarketingTask task,
                                            String stage,
                                            String reasonCode,
                                            String reasonMessage,
                                            long now) {
        return resetItemForAccountRetry(
                item,
                task,
                stage,
                reasonCode,
                reasonMessage,
                GroupCreationMarketingItemStatus.GROUP_CREATING.code(),
                null,
                now);
    }

    public boolean resetMarketingSendingItemForAccountRetry(GroupCreationMarketingItem item,
                                                            GroupCreationMarketingTask task,
                                                            String expectedCommandId,
                                                            String reasonCode,
                                                            String reasonMessage,
                                                            long now) {
        return resetItemForAccountRetry(
                item,
                task,
                STAGE_MARKETING_SEND,
                reasonCode,
                reasonMessage,
                GroupCreationMarketingItemStatus.MARKETING_SENDING.code(),
                expectedCommandId,
                now);
    }

    private boolean resetItemForAccountRetry(GroupCreationMarketingItem item,
                                             GroupCreationMarketingTask task,
                                             String stage,
                                             String reasonCode,
                                             String reasonMessage,
                                             int fromStatus,
                                             String expectedCommandId,
                                             long now) {
        GroupCreationMarketingRetryHistory history = appendFailure(item, stage, reasonCode, reasonMessage, now);
        GroupCreationMarketingAccountCandidate replacement = selectReplacement(task, history);
        String retryHistoryJson = history.toJson(objectMapper);
        if (replacement == null) {
            markNoAvailableAccount(item, fromStatus, expectedCommandId, retryHistoryJson, now);
            return false;
        }
        int updated = groupCreationMapper.resetItemForAccountRetry(
                item.getId(),
                replacement.getAccountId(),
                replacement.getAccountPhone(),
                replacement.getProtocolAccountId(),
                fromStatus,
                expectedCommandId,
                GroupCreationMarketingItemStatus.PENDING.code(),
                now,
                retryHistoryJson,
                now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "建群营销执行项状态已变化: " + item.getId());
        }
        updateItemSnapshot(item, replacement, retryHistoryJson);
        return true;
    }

    public GroupCreationMarketingAccountCandidate replaceClaimedItemAccountForRetry(GroupCreationMarketingItem item,
                                                                                    GroupCreationMarketingTask task,
                                                                                    String stage,
                                                                                    String reasonCode,
                                                                                    String reasonMessage,
                                                                                    long now) {
        GroupCreationMarketingRetryHistory history = appendFailure(item, stage, reasonCode, reasonMessage, now);
        GroupCreationMarketingAccountCandidate replacement = selectReplacement(task, history);
        String retryHistoryJson = history.toJson(objectMapper);
        if (replacement == null) {
            markNoAvailableAccount(
                    item,
                    GroupCreationMarketingItemStatus.GROUP_CREATING.code(),
                    null,
                    retryHistoryJson,
                    now);
            return null;
        }
        int updated = groupCreationMapper.updateItemAccountForClaimRetry(
                item.getId(),
                replacement.getAccountId(),
                replacement.getAccountPhone(),
                replacement.getProtocolAccountId(),
                retryHistoryJson,
                now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "建群营销执行项状态已变化: " + item.getId());
        }
        updateItemSnapshot(item, replacement, retryHistoryJson);
        return replacement;
    }

    private GroupCreationMarketingRetryHistory appendFailure(GroupCreationMarketingItem item,
                                                             String stage,
                                                             String reasonCode,
                                                             String reasonMessage,
                                                             long now) {
        return GroupCreationMarketingRetryHistory.parse(objectMapper, item.getRetryHistoryJson())
                .append(item, stage, reasonCode, reasonMessage, now);
    }

    private GroupCreationMarketingAccountCandidate selectReplacement(GroupCreationMarketingTask task,
                                                                     GroupCreationMarketingRetryHistory history) {
        List<Long> attemptedAccountIds = history.attemptedAccountIds();
        return groupCreationMapper.selectFirstAvailableAccountCandidateByGroupIdExcluding(
                task.getAccountGroupId(),
                attemptedAccountIds);
    }

    private void markNoAvailableAccount(GroupCreationMarketingItem item,
                                        int fromStatus,
                                        String expectedCommandId,
                                        String retryHistoryJson,
                                        long now) {
        int updated = groupCreationMapper.markItemNoAvailableAccount(
                item.getId(),
                REASON_NO_AVAILABLE_ACCOUNT,
                MESSAGE_NO_AVAILABLE_ACCOUNT,
                fromStatus,
                expectedCommandId,
                retryHistoryJson,
                now);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "建群营销执行项状态已变化: " + item.getId());
        }
        item.setRetryHistoryJson(retryHistoryJson);
    }

    private static void updateItemSnapshot(GroupCreationMarketingItem item,
                                           GroupCreationMarketingAccountCandidate replacement,
                                           String retryHistoryJson) {
        item.setAccountId(replacement.getAccountId());
        item.setAccountPhone(replacement.getAccountPhone());
        item.setProtocolAccountId(replacement.getProtocolAccountId());
        item.setRetryHistoryJson(retryHistoryJson);
    }
}
