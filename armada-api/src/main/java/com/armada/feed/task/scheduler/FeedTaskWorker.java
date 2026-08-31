package com.armada.feed.task.scheduler;

import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.selection.mapper.AccountFilterSelectionMapper;
import com.armada.account.selection.model.SelectedAccount;
import com.armada.feed.task.mapper.FeedTaskAccountMapper;
import com.armada.feed.task.mapper.FeedTaskMapper;
import com.armada.feed.task.model.entity.FeedTask;
import com.armada.feed.task.model.entity.FeedTaskAccount;
import com.armada.feed.task.model.enums.FeedTaskRunStatus;
import com.armada.feed.task.service.FeedTaskExpansionService;
import com.armada.feed.task.service.FeedTaskMessageCommandFactory;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 单个动态发布任务的一轮命令生成器。 */
@Component
@Profile("kafka")
public class FeedTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(FeedTaskWorker.class);
    private static final String REASON_ENQUEUE_UNKNOWN = "ENQUEUE_UNKNOWN";
    private static final String REASON_ACCOUNT_NOT_SENDABLE = "ACCOUNT_NOT_SENDABLE";
    private static final String REASON_NO_STATUS_RECIPIENTS = "NO_STATUS_RECIPIENTS";

    private final FeedTaskMapper taskMapper;
    private final FeedTaskAccountMapper accountMapper;
    private final AccountFilterSelectionMapper selectionMapper;
    private final AccountContactMapper contactMapper;
    private final FeedTaskExpansionService expansionService;
    private final FeedTaskMessageCommandFactory commandFactory;
    private final MessageSendPort messageSendPort;
    private final FeedTaskSchedulerProperties properties;
    private final FeedTaskLifecycleWorker lifecycleWorker;

    public FeedTaskWorker(FeedTaskMapper taskMapper,
                          FeedTaskAccountMapper accountMapper,
                          AccountFilterSelectionMapper selectionMapper,
                          AccountContactMapper contactMapper,
                          FeedTaskExpansionService expansionService,
                          FeedTaskMessageCommandFactory commandFactory,
                          MessageSendPort messageSendPort,
                          FeedTaskSchedulerProperties properties,
                          FeedTaskLifecycleWorker lifecycleWorker) {
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.selectionMapper = selectionMapper;
        this.contactMapper = contactMapper;
        this.expansionService = expansionService;
        this.commandFactory = commandFactory;
        this.messageSendPort = messageSendPort;
        this.properties = properties;
        this.lifecycleWorker = lifecycleWorker;
    }

    /** 抢占并执行一个任务轮次。 */
    @Transactional(rollbackFor = Exception.class)
    public void runRound(Long tenantId, Long taskId) {
        Long previous = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            runRoundInTenant(tenantId, taskId);
        } finally {
            restore(previous);
        }
    }

    private void runRoundInTenant(Long tenantId, Long taskId) {
        FeedTask task = taskMapper.selectById(taskId);
        if (task == null || task.getTaskStatus() == null
                || task.getTaskStatus() != FeedTaskRunStatus.RUNNING.code()) {
            return;
        }
        long now = System.currentTimeMillis();
        long nextRunAt = now + Math.max(1000L, properties.getRoundDelayMs());
        long roundNo = (task.getCurrentRoundNo() == null ? 0L : task.getCurrentRoundNo()) + 1L;
        if (taskMapper.claimDueRound(taskId, now, nextRunAt) == 0) {
            return;
        }
        expandRollingTask(task, now);
        List<FeedTaskAccount> accounts = accountMapper.selectDispatchable(
                taskId, Math.max(1, task.getConcurrency()));
        if (accounts.isEmpty()) {
            lifecycleWorker.completeIfDrained(tenantId, taskId);
            return;
        }
        dispatchAccounts(tenantId, task, accounts, roundNo, now);
        lifecycleWorker.completeIfDrained(tenantId, taskId);
    }

    private void expandRollingTask(FeedTask task, long now) {
        if (!"rolling".equals(task.getTaskMode())
                || task.getTaskPlannedEndAt() == null
                || task.getTaskPlannedEndAt() <= now) {
            return;
        }
        int inserted = expansionService.expand(task, Math.max(1, task.getConcurrency()), now);
        if (inserted > 0) {
            taskMapper.incrementTotalAccountNum(task.getId(), inserted, now);
        }
    }

    private void dispatchAccounts(Long tenantId,
                                  FeedTask task,
                                  List<FeedTaskAccount> accounts,
                                  long roundNo,
                                  long now) {
        Map<Long, SelectedAccount> facts = protocolFacts(accounts.stream()
                .map(FeedTaskAccount::getAccountId)
                .toList());
        FeedTaskMessageCommandFactory.ComposedFeedStatus content = commandFactory.composeContent(task);
        List<MessageSendCommand> commands = new ArrayList<>();
        List<FeedTaskAccount> claimed = new ArrayList<>();
        for (FeedTaskAccount account : accounts) {
            SelectedAccount fact = facts.get(account.getAccountId());
            if (fact == null) {
                failAccount(task.getId(), account.getId(), REASON_ACCOUNT_NOT_SENDABLE,
                        "账号当前不可发送", now);
                continue;
            }
            List<String> statusJids = statusJids(account.getAccountId());
            if (statusJids.isEmpty()) {
                failAccount(task.getId(), account.getId(), REASON_NO_STATUS_RECIPIENTS,
                        "账号没有可见动态的通讯录联系人", now);
                continue;
            }
            String commandId = commandFactory.newCommandId();
            if (accountMapper.markSending(
                    account.getId(), account.getSendStatus(), commandId, roundNo, now) == 0) {
                continue;
            }
            commands.add(commandFactory.toCommand(
                    task, account, fact, content, statusJids, roundNo, commandId));
            claimed.add(account);
        }
        int rejected = enqueueInBatches(commands, claimed, now);
        log.info("动态发布任务轮次已生成命令 tenantId={} taskId={} roundNo={} accounts={} commands={} rejected={}",
                tenantId, task.getId(), roundNo, accounts.size(), commands.size(), rejected);
    }

    private Map<Long, SelectedAccount> protocolFacts(List<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        List<SelectedAccount> rows = selectionMapper.selectSendableByIds(
                accountIds,
                AccountFilterSelectionMapper.ACCOUNT_STATE_NORMAL,
                AccountFilterSelectionMapper.ACCOUNT_STATE_EXPORTED);
        Map<Long, SelectedAccount> facts = new HashMap<>();
        if (rows != null) {
            for (SelectedAccount row : rows) {
                facts.put(row.accountId(), row);
            }
        }
        return facts;
    }

    private List<String> statusJids(Long accountId) {
        int limit = Math.max(1, properties.getStatusRecipientLimit());
        return contactMapper.selectNamedByAccount(accountId, limit).stream()
                .map(contact -> contact.getContactJid())
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private int enqueueInBatches(List<MessageSendCommand> commands,
                                 List<FeedTaskAccount> claimed,
                                 long now) {
        if (commands.isEmpty()) {
            return 0;
        }
        int batchSize = Math.max(1, Math.min(500, properties.getOutboxBatchSize()));
        int rejectedCount = 0;
        for (int start = 0; start < commands.size(); start += batchSize) {
            int end = Math.min(commands.size(), start + batchSize);
            List<MessageSendCommand> batch = commands.subList(start, end);
            List<FeedTaskAccount> batchAccounts = claimed.subList(start, end);
            MessageSendEnqueueResult result = messageSendPort.enqueue(batch);
            if (result == null || result.items().size() != batch.size()) {
                throw new IllegalStateException("动态发布命令入队结果数量与命令不一致");
            }
            for (int i = 0; i < batch.size(); i++) {
                MessageSendEnqueueItem item = result.items().get(i);
                if (item != null && item.accepted()) {
                    continue;
                }
                FeedTaskAccount account = batchAccounts.get(i);
                String reasonCode = item == null ? REASON_ENQUEUE_UNKNOWN : item.reasonCode();
                String reasonMessage = item == null ? "入队结果缺失" : item.reasonMessage();
                if (failAccount(account.getTaskId(), account.getId(), reasonCode, reasonMessage, now)) {
                    rejectedCount++;
                }
            }
        }
        return rejectedCount;
    }

    private boolean failAccount(Long taskId, Long accountRowId, String reasonCode, String reasonMessage, long now) {
        if (accountMapper.markFailed(accountRowId, reasonCode, reasonMessage, now) == 0) {
            return false;
        }
        taskMapper.incrementFailedAccountNum(taskId, now);
        return true;
    }

    private static void restore(Long previous) {
        if (previous == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previous);
        }
    }
}
