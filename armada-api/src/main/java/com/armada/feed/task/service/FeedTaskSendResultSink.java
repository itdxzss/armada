package com.armada.feed.task.service;

import com.armada.feed.task.mapper.FeedTaskAccountMapper;
import com.armada.feed.task.mapper.FeedTaskMapper;
import com.armada.feed.task.model.entity.FeedTask;
import com.armada.feed.task.model.entity.FeedTaskAccount;
import com.armada.feed.task.model.enums.FeedTaskRunStatus;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedEvent;
import com.armada.platform.kafka.consumer.message.ProtocolMessageSendResultReportedSink;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 动态发布任务发送结果回写。 */
@Component
public class FeedTaskSendResultSink implements ProtocolMessageSendResultReportedSink {

    private static final Logger log = LoggerFactory.getLogger(FeedTaskSendResultSink.class);
    private static final int FAIL_REASON_MAX_LENGTH = 255;

    private final FeedTaskMapper taskMapper;
    private final FeedTaskAccountMapper accountMapper;

    public FeedTaskSendResultSink(FeedTaskMapper taskMapper, FeedTaskAccountMapper accountMapper) {
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
    }

    @Override
    public boolean supports(ProtocolMessageSendResultReportedEvent event) {
        return event != null && FeedTaskMessageCommandFactory.SOURCE_FEED_TASK.equals(event.source());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleSendResultReported(ProtocolMessageSendResultReportedEvent event) {
        Long previous = TenantContext.get();
        TenantContext.set(event.tenantId());
        try {
            if (event.success()) {
                applySuccess(event);
            } else {
                applyFailure(event);
            }
            completeEndedRollingTask(event.feedTaskId());
        } finally {
            restore(previous);
        }
    }

    private void applySuccess(ProtocolMessageSendResultReportedEvent event) {
        long now = System.currentTimeMillis();
        if (accountMapper.markSuccess(event.feedTaskAccountId(), event.messageId(), now) == 0) {
            log.debug("动态发布任务重复成功回执,跳过计数 feedTaskAccountId={} commandId={}",
                    event.feedTaskAccountId(), event.commandId());
            return;
        }
        taskMapper.incrementSuccessAccountNum(event.feedTaskId(), now);
    }

    private void applyFailure(ProtocolMessageSendResultReportedEvent event) {
        FeedTaskAccount row = accountMapper.selectById(event.feedTaskAccountId());
        if (row == null) {
            log.warn("动态发布任务失败回执找不到账号明细 feedTaskAccountId={} commandId={}",
                    event.feedTaskAccountId(), event.commandId());
            return;
        }
        long now = System.currentTimeMillis();
        String failCode = event.reasonCode();
        String failReason = truncate(event.reasonMessage());
        if (retryable(row)) {
            accountMapper.markRetrying(row.getId(), failCode, failReason, now);
            return;
        }
        if (accountMapper.markFailed(row.getId(), failCode, failReason, now) > 0) {
            taskMapper.incrementFailedAccountNum(event.feedTaskId(), now);
        }
    }

    private void completeEndedRollingTask(Long taskId) {
        FeedTask task = taskId == null ? null : taskMapper.selectById(taskId);
        long now = System.currentTimeMillis();
        if (task == null
                || task.getTaskStatus() == null
                || task.getTaskStatus() != FeedTaskRunStatus.RUNNING.code()
                || !"rolling".equals(task.getTaskMode())
                || task.getTaskPlannedEndAt() == null
                || task.getTaskPlannedEndAt() > now
                || accountMapper.countOpen(taskId) > 0) {
            return;
        }
        taskMapper.complete(taskId, now);
    }

    private static boolean retryable(FeedTaskAccount row) {
        int retryNum = row.getRetryNum() == null ? 0 : row.getRetryNum();
        int retryMax = row.getRetryMax() == null ? 0 : row.getRetryMax();
        return retryNum < retryMax;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= FAIL_REASON_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, FAIL_REASON_MAX_LENGTH);
    }

    private static void restore(Long previous) {
        if (previous == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previous);
        }
    }
}
