package com.armada.marketing.service.impl;

import com.armada.marketing.mapper.GroupCreationMarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.entity.GroupCreationMarketingItem;
import com.armada.marketing.model.entity.GroupCreationMarketingTask;
import com.armada.marketing.model.enums.GroupCreationMarketingItemStatus;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedSink;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 营销消息发送结果处理器。
 *
 * <p>协议层发送完 {@code message.send.requested} 后发布结果事件,这里负责把事件回写到
 * {@code marketing_task_send_attempt},并在首次回写结果时累计任务发送成功/失败计数；成功结果还会按群 JID
 * 维护累计成功群事实。</p>
 */
@Service
public class MarketingSendResultServiceImpl implements ProtocolMessageSendResultReportedSink {
    private static final Logger log = LoggerFactory.getLogger(MarketingSendResultServiceImpl.class);
    private static final String SOURCE_GROUP_CREATION_MARKETING = "group_creation_marketing";

    private final MarketingTaskMapper taskMapper;
    private final GroupCreationMarketingTaskMapper groupCreationMapper;
    private final GroupCreationMarketingRetryService retryService;

    public MarketingSendResultServiceImpl(MarketingTaskMapper taskMapper,
                                          GroupCreationMarketingTaskMapper groupCreationMapper,
                                          GroupCreationMarketingRetryService retryService) {
        this.taskMapper = taskMapper;
        this.groupCreationMapper = groupCreationMapper;
        this.retryService = retryService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleSendResultReported(ProtocolMessageSendResultReportedEvent event) {
        Long previousTenant = TenantContext.get();
        TenantContext.set(event.tenantId());
        try {
            long resultAt = event.timestamp() == null ? System.currentTimeMillis() : event.timestamp();
            if (isGroupCreationMarketing(event)) {
                handleGroupCreationMarketingResult(event, resultAt);
                return;
            }
            int updated = event.success()
                    ? taskMapper.markAttemptSuccess(event.attemptId(), event.messageId(), event.groupJid(), resultAt)
                    : taskMapper.markAttemptFailed(event.attemptId(), event.reasonCode(),
                            event.reasonMessage(), event.groupJid(), resultAt);
            // markAttempt* 只更新 SUBMITTED 状态;重复事件 updated=0,避免任务计数重复累加。
            if (updated > 0) {
                boolean newSuccessfulGroup = false;
                if (event.success()) {
                    taskMapper.markTargetSuccessFromAttempt(event.targetId(), event.attemptId(), resultAt);
                    newSuccessfulGroup = recordSuccessfulGroup(event, resultAt);
                } else {
                    taskMapper.markTargetFailedFromAttempt(event.targetId(), event.attemptId(),
                            event.reasonCode(), event.reasonMessage(), resultAt);
                }
                taskMapper.incrementTaskSendCounters(event.marketingTaskId(),
                        event.success() ? 1 : 0,
                        event.success() ? 0 : 1,
                        resultAt);
                if (event.success()) {
                    groupCreationMapper.markItemSuccessByMarketingAttemptId(event.attemptId(), resultAt);
                } else {
                    groupCreationMapper.markItemFailedByMarketingAttemptId(event.attemptId(),
                            event.reasonCode(), event.reasonMessage(), resultAt);
                }
                log.info("营销发送结果已回写 tenantId={} taskId={} targetId={} attemptId={} roundNo={} "
                                + "commandId={} protocolAccountId={} groupJid={} success={} messageId={} "
                                + "reasonCode={} workerId={} newSuccessfulGroup={}",
                        event.tenantId(), event.marketingTaskId(), event.targetId(), event.attemptId(),
                        event.roundNo(), event.commandId(), event.protocolAccountId(), event.groupJid(),
                        event.success(), event.messageId(), event.reasonCode(), event.workerId(), newSuccessfulGroup);
            } else {
                log.info("营销发送结果已跳过 tenantId={} taskId={} targetId={} attemptId={} roundNo={} "
                                + "commandId={} protocolAccountId={} groupJid={} success={} reason=duplicate_or_final",
                        event.tenantId(), event.marketingTaskId(), event.targetId(), event.attemptId(),
                        event.roundNo(), event.commandId(), event.protocolAccountId(), event.groupJid(),
                        event.success());
            }
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previousTenant);
            }
        }
    }

    /**
     * 记录普通营销任务首次成功触达的群，并同步递增主表累计数。
     *
     * <p>唯一键承担跨轮次、跨账号并发去重；事实写入和主表递增处于当前结果回调事务中，
     * 任一步异常都会连同 attempt 成功状态一起回滚。</p>
     */
    private boolean recordSuccessfulGroup(ProtocolMessageSendResultReportedEvent event, long resultAt) {
        String groupJid = taskMapper.selectSuccessfulAttemptGroupJid(event.marketingTaskId(), event.attemptId());
        if (!StringUtils.hasText(groupJid)) {
            log.warn("营销成功结果缺少有效群JID,累计群组数未更新 tenantId={} taskId={} targetId={} attemptId={}",
                    event.tenantId(), event.marketingTaskId(), event.targetId(), event.attemptId());
            return false;
        }
        int inserted = taskMapper.insertSuccessfulGroupFromAttempt(
                event.tenantId(), event.marketingTaskId(), event.attemptId(), resultAt);
        if (inserted == 0) {
            return false;
        }
        if (inserted != 1) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "累计成功群组事实写入数量异常: taskId=" + event.marketingTaskId()
                            + ", attemptId=" + event.attemptId() + ", inserted=" + inserted);
        }
        int incremented = taskMapper.incrementTaskSuccessfulGroupCount(event.marketingTaskId(), resultAt);
        if (incremented != 1) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "累计成功群组数量更新失败: taskId=" + event.marketingTaskId()
                            + ", attemptId=" + event.attemptId());
        }
        return true;
    }

    private void handleGroupCreationMarketingResult(ProtocolMessageSendResultReportedEvent event, long resultAt) {
        if (!event.success()) {
            handleGroupCreationMarketingFailure(event, resultAt);
            return;
        }
        int updated = event.success()
                ? groupCreationMapper.markItemSuccessByCommandId(
                        event.groupCreationItemId(), event.commandId(), event.groupJid(), event.messageId(), resultAt)
                : 0;
        if (updated > 0) {
            log.info("建群营销发送结果已回写 tenantId={} taskId={} itemId={} commandId={} "
                            + "protocolAccountId={} groupJid={} success={} messageId={} reasonCode={} workerId={}",
                    event.tenantId(), event.groupCreationTaskId(), event.groupCreationItemId(), event.commandId(),
                    event.protocolAccountId(), event.groupJid(), event.success(), event.messageId(),
                    event.reasonCode(), event.workerId());
        } else {
            log.info("建群营销发送结果已跳过 tenantId={} taskId={} itemId={} commandId={} "
                            + "protocolAccountId={} groupJid={} success={} reason=duplicate_or_final",
                    event.tenantId(), event.groupCreationTaskId(), event.groupCreationItemId(), event.commandId(),
                    event.protocolAccountId(), event.groupJid(), event.success());
        }
    }

    private void handleGroupCreationMarketingFailure(ProtocolMessageSendResultReportedEvent event, long resultAt) {
        GroupCreationMarketingItem item = groupCreationMapper.selectItemById(event.groupCreationItemId());
        if (!matchesCurrentMarketingSend(item, event.commandId())) {
            log.info("建群营销发送失败结果已跳过 tenantId={} taskId={} itemId={} commandId={} "
                            + "protocolAccountId={} groupJid={} success=false reason=duplicate_or_final",
                    event.tenantId(), event.groupCreationTaskId(), event.groupCreationItemId(), event.commandId(),
                    event.protocolAccountId(), event.groupJid());
            return;
        }
        GroupCreationMarketingTask task = groupCreationMapper.selectTaskById(
                event.groupCreationTaskId() == null ? item.getTaskId() : event.groupCreationTaskId());
        if (task == null) {
            log.info("建群营销发送失败结果已跳过 tenantId={} taskId={} itemId={} commandId={} "
                            + "protocolAccountId={} groupJid={} success=false reason=task_not_found",
                    event.tenantId(), event.groupCreationTaskId(), event.groupCreationItemId(), event.commandId(),
                    event.protocolAccountId(), event.groupJid());
            return;
        }
        String reasonCode = StringUtils.hasText(event.reasonCode()) ? event.reasonCode() : "MESSAGE_SEND_FAILED";
        String reasonMessage = StringUtils.hasText(event.reasonMessage()) ? event.reasonMessage() : reasonCode;
        boolean retried = retryService.resetMarketingSendingItemForAccountRetry(
                item,
                task,
                event.commandId(),
                reasonCode,
                reasonMessage,
                resultAt);
        log.info("建群营销发送失败已处理 tenantId={} taskId={} itemId={} commandId={} "
                        + "protocolAccountId={} groupJid={} retried={} reasonCode={} workerId={}",
                event.tenantId(), task.getId(), item.getId(), event.commandId(),
                event.protocolAccountId(), event.groupJid(), retried, reasonCode, event.workerId());
    }

    private static boolean matchesCurrentMarketingSend(GroupCreationMarketingItem item, String commandId) {
        return item != null
                && Objects.equals(item.getCommandId(), commandId)
                && Integer.valueOf(GroupCreationMarketingItemStatus.MARKETING_SENDING.code()).equals(item.getStatus());
    }

    private static boolean isGroupCreationMarketing(ProtocolMessageSendResultReportedEvent event) {
        return event != null && SOURCE_GROUP_CREATION_MARKETING.equals(event.source());
    }
}
