package com.armada.marketing.service.impl;

import com.armada.marketing.mapper.GroupCreationMarketingTaskMapper;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.entity.GroupCreationMarketingItem;
import com.armada.marketing.model.entity.GroupCreationMarketingTask;
import com.armada.marketing.model.enums.GroupCreationMarketingItemStatus;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedSink;
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
 * {@code marketing_task_send_attempt},并在首次回写成功时累计任务发送成功/失败计数。</p>
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
                if (event.success()) {
                    taskMapper.markTargetSuccessFromAttempt(event.targetId(), event.attemptId(), resultAt);
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
                                + "reasonCode={} workerId={}",
                        event.tenantId(), event.marketingTaskId(), event.targetId(), event.attemptId(),
                        event.roundNo(), event.commandId(), event.protocolAccountId(), event.groupJid(),
                        event.success(), event.messageId(), event.reasonCode(), event.workerId());
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
