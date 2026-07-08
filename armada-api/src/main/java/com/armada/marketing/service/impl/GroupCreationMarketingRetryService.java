package com.armada.marketing.service.impl;

import com.armada.marketing.mapper.GroupCreationMarketingTaskMapper;
import com.armada.marketing.model.entity.GroupCreationMarketingItem;
import com.armada.marketing.model.entity.GroupCreationMarketingTask;
import com.armada.marketing.model.enums.GroupCreationMarketingItemStatus;
import com.armada.marketing.model.support.GroupCreationMarketingClaimRetryAccountUpdate;
import com.armada.marketing.model.support.GroupCreationMarketingNoAvailableAccountUpdate;
import com.armada.marketing.model.support.GroupCreationMarketingRetryHistory;
import com.armada.marketing.model.support.GroupCreationMarketingRetryResetUpdate;
import com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 建群营销换号重试服务。
 *
 * <p>记录执行项失败历史,排除已尝试账号后选择同账号分组内的下一个可用账号。
 * 有可替换账号时重置执行项回待处理或更新当前抢占账号;没有可替换账号时将执行项终态放弃。</p>
 */
@Service
public class GroupCreationMarketingRetryService {

    /** 账号在线/可用性检查阶段。 */
    public static final String STAGE_ACCOUNT_CHECK = "ACCOUNT_CHECK";

    /** 协议建群阶段。 */
    public static final String STAGE_GROUP_CREATE = "GROUP_CREATE";

    /** 营销消息发送阶段。 */
    public static final String STAGE_MARKETING_SEND = "MARKETING_SEND";

    /** 无可用替换账号时的终态原因码。 */
    public static final String REASON_NO_AVAILABLE_ACCOUNT = "NO_AVAILABLE_ACCOUNT";

    /** 无可用替换账号时展示给运营的原因。 */
    public static final String MESSAGE_NO_AVAILABLE_ACCOUNT = "没有可用账号";

    /** 建群营销任务 Mapper。 */
    private final GroupCreationMarketingTaskMapper groupCreationMapper;

    /** 重试历史 JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /**
     * 注入换号重试依赖。
     *
     * @param groupCreationMapper 建群营销任务 Mapper
     * @param objectMapper        JSON 序列化器
     */
    public GroupCreationMarketingRetryService(GroupCreationMarketingTaskMapper groupCreationMapper,
                                              ObjectMapper objectMapper) {
        this.groupCreationMapper = groupCreationMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 建群阶段失败后尝试换号重试。
     *
     * <p>成功找到替换账号时,执行项会被重置为待处理并写入新账号快照;没有账号可替换时终态放弃。</p>
     *
     * @param item          当前执行项
     * @param task          所属建群营销任务
     * @param stage         失败阶段
     * @param reasonCode    失败原因码
     * @param reasonMessage 失败原因描述
     * @param now           当前时间(epoch 毫秒)
     * @return true 表示已重置为待处理,false 表示已终态放弃
     */
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

    /**
     * 营销发送阶段失败后尝试换号重试。
     *
     * <p>使用 expectedCommandId 保护当前发送命令,避免旧回执把新一轮执行项错误重置。</p>
     *
     * @param item              当前执行项
     * @param task              所属建群营销任务
     * @param expectedCommandId 当前营销发送命令 ID
     * @param reasonCode        失败原因码
     * @param reasonMessage     失败原因描述
     * @param now               当前时间(epoch 毫秒)
     * @return true 表示已重置为待处理,false 表示已终态放弃
     */
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
        GroupCreationMarketingRetryResetUpdate update = new GroupCreationMarketingRetryResetUpdate();
        update.setId(item.getId());
        update.setAccountId(replacement.getAccountId());
        update.setAccountPhone(replacement.getAccountPhone());
        update.setProtocolAccountId(replacement.getProtocolAccountId());
        update.setFromStatus(fromStatus);
        update.setExpectedCommandId(expectedCommandId);
        update.setPendingStatus(GroupCreationMarketingItemStatus.PENDING.code());
        update.setNextRunAt(now);
        update.setRetryHistoryJson(retryHistoryJson);
        update.setUpdatedAt(now);
        int updated = groupCreationMapper.resetItemForAccountRetry(update);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "建群营销执行项状态已变化: " + item.getId());
        }
        updateItemSnapshot(item, replacement, retryHistoryJson);
        return true;
    }

    /**
     * 执行项已抢占后替换当前账号。
     *
     * <p>该方法不重置执行项状态,只更新账号快照和重试历史,供 worker 在真正调用协议建群前换成可用账号。</p>
     *
     * @param item          当前执行项
     * @param task          所属建群营销任务
     * @param stage         失败阶段
     * @param reasonCode    失败原因码
     * @param reasonMessage 失败原因描述
     * @param now           当前时间(epoch 毫秒)
     * @return 替换后的账号;没有可用账号时返回 null 且执行项已终态放弃
     */
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
        GroupCreationMarketingClaimRetryAccountUpdate update = new GroupCreationMarketingClaimRetryAccountUpdate();
        update.setId(item.getId());
        update.setAccountId(replacement.getAccountId());
        update.setAccountPhone(replacement.getAccountPhone());
        update.setProtocolAccountId(replacement.getProtocolAccountId());
        update.setRetryHistoryJson(retryHistoryJson);
        update.setUpdatedAt(now);
        int updated = groupCreationMapper.updateItemAccountForClaimRetry(update);
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
        GroupCreationMarketingNoAvailableAccountUpdate update = new GroupCreationMarketingNoAvailableAccountUpdate();
        update.setId(item.getId());
        update.setReasonCode(REASON_NO_AVAILABLE_ACCOUNT);
        update.setReasonMessage(MESSAGE_NO_AVAILABLE_ACCOUNT);
        update.setFromStatus(fromStatus);
        update.setExpectedCommandId(expectedCommandId);
        update.setRetryHistoryJson(retryHistoryJson);
        update.setFinishedAt(now);
        int updated = groupCreationMapper.markItemNoAvailableAccount(update);
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
