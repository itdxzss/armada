package com.armada.contact.task.scheduler;

import com.armada.account.selection.mapper.AccountFilterSelectionMapper;
import com.armada.account.selection.model.SelectedAccount;
import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.entity.ContactFriendTaskAccount;
import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import com.armada.contact.task.model.enums.ContactTaskRunStatus;
import com.armada.contact.task.service.ContactTaskMessageCommandFactory;
import com.armada.contact.task.service.ContactTaskCloudPreparationService;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.port.MessageSendPort;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 单个通讯录营销任务的一轮发送生成器。
 *
 * <p>一轮只做一件事：把有 PENDING 收件人的账号各排一批出去。真实发送在协议层，
 * 不在本事务内同步执行。轮次抢占（{@code claimDueRound}）与收件人抢占
 * （{@code claimForSend}）是两道并发闸门，缺一就会重复投递。</p>
 *
 * <p><b>本类刻意不标注 {@code @Component}</b>：构造参数含函数式回调，由
 * {@link ContactTaskSchedulerConfiguration} 显式构造，以便纯 Mockito 测试。</p>
 */
public class ContactTaskRoundWorker {

    private static final Logger log = LoggerFactory.getLogger(ContactTaskRoundWorker.class);

    /** 入队结果缺失时使用的稳定原因码。 */
    private static final String REASON_ENQUEUE_UNKNOWN = "ENQUEUE_UNKNOWN";

    private final ContactFriendTaskMapper taskMapper;
    private final ContactFriendTaskAccountMapper accountMapper;
    private final ContactFriendTaskRecipientMapper recipientMapper;
    private final AccountFilterSelectionMapper selectionMapper;
    private final ContactTaskMessageCommandFactory commandFactory;
    private final MessageSendPort messageSendPort;
    private final ContactTaskSchedulerProperties properties;
    private final Clock clock;
    private final Random random;
    private final DrainedTaskSettler settler;
    private final ContactTaskCloudPreparationService preparationService;

    /** 收件人排干后的收尾回调；生产装配传 {@code ContactTaskLifecycleWorker::completeDrainedTask}。 */
    @FunctionalInterface
    public interface DrainedTaskSettler {

        /**
         * 收敛并完成已排干的任务。
         *
         * @param tenantId 租户 ID
         * @param taskId 任务 ID
         */
        void settle(Long tenantId, Long taskId);
    }

    /**
     * 创建轮次执行器。
     *
     * @param taskMapper 任务主表数据访问
     * @param accountMapper 任务账号读模型数据访问
     * @param recipientMapper 收件人明细数据访问
     * @param selectionMapper 账号协议事实复查
     * @param commandFactory 消息命令组装器
     * @param messageSendPort 协议 outbox 端口
     * @param properties 调度参数
     * @param clock 系统时钟
     * @param random 发送间隔随机源
     * @param settler 排干后的收尾回调
     * @param preparationService Android 云端 LID 名单准备器
     */
    public ContactTaskRoundWorker(ContactFriendTaskMapper taskMapper,
                                  ContactFriendTaskAccountMapper accountMapper,
                                  ContactFriendTaskRecipientMapper recipientMapper,
                                  AccountFilterSelectionMapper selectionMapper,
                                  ContactTaskMessageCommandFactory commandFactory,
                                  MessageSendPort messageSendPort,
                                  ContactTaskSchedulerProperties properties,
                                  Clock clock,
                                  Random random,
                                  DrainedTaskSettler settler,
                                  ContactTaskCloudPreparationService preparationService) {
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.recipientMapper = recipientMapper;
        this.selectionMapper = selectionMapper;
        this.commandFactory = commandFactory;
        this.messageSendPort = messageSendPort;
        this.properties = properties;
        this.clock = clock;
        this.random = random;
        this.settler = settler;
        this.preparationService = preparationService;
    }

    /**
     * 生成一个任务的一轮发送命令。
     *
     * <p>调度器在无请求上下文的后台线程调用，必须显式设置 TenantContext，
     * 让 MyBatis 租户拦截器把所有读写限制在当前租户内。</p>
     *
     * @param tenantId 租户 ID
     * @param taskId 任务 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void runRound(Long tenantId, Long taskId) {
        Long previous = TenantContext.get();
        TenantContext.set(tenantId);
        try {
            doRunRound(tenantId, taskId);
        } finally {
            if (previous == null) {
                TenantContext.clear();
            } else {
                TenantContext.set(previous);
            }
        }
    }

    private void doRunRound(Long tenantId, Long taskId) {
        ContactFriendTask task = taskMapper.selectByIdForUpdate(taskId);
        if (task == null) {
            log.warn("通讯录任务轮次跳过:任务不存在 taskId={}", taskId);
            return;
        }
        if (!Integer.valueOf(ContactTaskRunStatus.RUNNING.code()).equals(task.getRunStatus())) {
            log.debug("通讯录任务轮次跳过:任务非进行中 tenantId={} taskId={} runStatus={}",
                    tenantId, taskId, task.getRunStatus());
            return;
        }
        long now = clock.millis();
        // 每个任务账号最多一条在途，异常结果落库后才允许取下一条。
        int perAccount = 1;
        long nextRoundAt = now + 1000L;
        // 历史数据或并发操作可能把任务提前置为进行中，worker 不能越过计划开始时间发消息
        if (task.getTaskStartAt() != null && task.getTaskStartAt() > now) {
            taskMapper.postponeDueRound(taskId, now, task.getTaskStartAt());
            log.warn("通讯录任务轮次退回等待:尚未到计划开始时间 tenantId={} taskId={} taskStartAt={}",
                    tenantId, taskId, task.getTaskStartAt());
            return;
        }
        preparationService.prepare(task, now);
        int accountLimit = task.getConcurrency() == null || task.getConcurrency() < 1
                ? 1
                : task.getConcurrency();
        List<Long> taskAccountIds =
                recipientMapper.selectAccountIdsWithPending(taskId, accountLimit);
        if (taskAccountIds.isEmpty()) {
            if (recipientMapper.countUnfinished(taskId) == 0L && accountMapper.countPreparing(taskId) == 0L) {
                settler.settle(tenantId, taskId);
            } else {
                // 仍在采集名单或等待回执时保留任务，不能把零收件人误判为排干。
                taskMapper.postponeDueRound(taskId, now, nextRoundAt);
            }
            return;
        }
        long plannedCount = (long) taskAccountIds.size() * perAccount;
        long backlogThreshold = Math.max(1, properties.getBacklogMultiplier()) * plannedCount;
        // 下游协议层积压过高时只推迟，避免持续生成新命令把 outbox 堆穿
        if (recipientMapper.countInFlight(taskId) >= backlogThreshold) {
            taskMapper.postponeDueRound(taskId, now, nextRoundAt);
            log.info("通讯录任务轮次因积压推迟 tenantId={} taskId={} plannedCount={} backlogThreshold={}",
                    tenantId, taskId, plannedCount, backlogThreshold);
            return;
        }
        // claimDueRound 是并发闸门：只有一个线程能把到期任务推进到下一轮
        if (taskMapper.claimDueRound(taskId, now, nextRoundAt) == 0) {
            log.debug("通讯录任务轮次抢占失败 tenantId={} taskId={}", tenantId, taskId);
            return;
        }
        long roundNo = (task.getCurrentRoundNo() == null ? 0L : task.getCurrentRoundNo()) + 1L;
        executeClaimedRound(tenantId, task, taskAccountIds, roundNo, now, perAccount);
    }

    private void executeClaimedRound(Long tenantId,
                                     ContactFriendTask task,
                                     List<Long> taskAccountIds,
                                     long roundNo,
                                     long now,
                                     int perAccount) {
        List<ContactFriendTaskAccount> accountRows = new ArrayList<>(taskAccountIds.size());
        List<Long> armadaAccountIds = new ArrayList<>(taskAccountIds.size());
        for (Long taskAccountId : taskAccountIds) {
            ContactFriendTaskAccount row = accountMapper.selectById(taskAccountId);
            if (row != null) {
                accountRows.add(row);
                armadaAccountIds.add(row.getAccountId());
            }
        }
        if (accountRows.isEmpty()) {
            return;
        }
        Map<Long, SelectedAccount> facts = protocolFacts(armadaAccountIds);
        ContactTaskMessageCommandFactory.ComposedContactMessage content =
                commandFactory.composeContent(task);
        List<MessageSendCommand> commands = new ArrayList<>();
        List<ContactFriendTaskRecipient> claimed = new ArrayList<>();
        for (ContactFriendTaskAccount accountRow : accountRows) {
            SelectedAccount protocolFact = facts.get(accountRow.getAccountId());
            if (protocolFact == null) {
                accountMapper.stopAccount(accountRow.getId(), "ACCOUNT_UNAVAILABLE", now);
                recipientMapper.skipPendingByAccount(accountRow.getId(), "ACCOUNT_UNAVAILABLE", now);
                log.info("通讯录任务轮次跳过不可发送账号 tenantId={} taskId={} accountId={}",
                        tenantId, task.getId(), accountRow.getAccountId());
                continue;
            }
            accountMapper.markRunning(accountRow.getId(), now);
            for (ContactFriendTaskRecipient recipient
                    : recipientMapper.selectPendingByAccount(accountRow.getId(), perAccount)) {
                String commandId = commandFactory.newCommandId();
                if (recipientMapper.claimForSend(recipient.getId(), roundNo, commandId, now) == 0) {
                    continue;
                }
                recipient.setCommandId(commandId);
                commands.add(commandFactory.toCommand(
                        task, accountRow, recipient, protocolFact, content,
                        roundNo, 0L, random));
                claimed.add(recipient);
            }
        }
        if (commands.isEmpty()) {
            return;
        }
        int rejected = enqueueInBatches(commands, claimed, now);
        log.info("通讯录任务轮次发送命令已生成 tenantId={} taskId={} roundNo={} accounts={} "
                        + "commands={} rejected={}",
                tenantId, task.getId(), roundNo, accountRows.size(), commands.size(), rejected);
    }

    /** 一次批量复查本轮账号的协议事实，读不到即视为当前不可发送。 */
    private Map<Long, SelectedAccount> protocolFacts(List<Long> armadaAccountIds) {
        if (armadaAccountIds.isEmpty()) {
            return Map.of();
        }
        List<SelectedAccount> rows = selectionMapper.selectSendableByIds(
                armadaAccountIds,
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

    /** 按批写 outbox，本地拒绝的收件人立刻落终态失败并计数。 */
    private int enqueueInBatches(List<MessageSendCommand> commands,
                                 List<ContactFriendTaskRecipient> claimed,
                                 long now) {
        int batchSize = Math.max(1, Math.min(500, properties.getOutboxBatchSize()));
        int rejectedCount = 0;
        for (int start = 0; start < commands.size(); start += batchSize) {
            int end = Math.min(commands.size(), start + batchSize);
            List<MessageSendCommand> batch = commands.subList(start, end);
            List<ContactFriendTaskRecipient> batchRecipients = claimed.subList(start, end);
            MessageSendEnqueueResult result = messageSendPort.enqueue(batch);
            if (result == null || result.items().size() != batch.size()) {
                throw new IllegalStateException("通讯录消息入队结果数量与命令不一致");
            }
            for (int i = 0; i < batch.size(); i++) {
                MessageSendEnqueueItem item = result.items().get(i);
                if (item != null && item.accepted()) {
                    continue;
                }
                ContactFriendTaskRecipient recipient = batchRecipients.get(i);
                String reasonCode = item == null ? REASON_ENQUEUE_UNKNOWN : item.reasonCode();
                String reasonMessage = item == null ? "入队结果缺失" : item.reasonMessage();
                if (recipientMapper.markFailed(
                        recipient.getId(), reasonCode, reasonMessage, now) > 0) {
                    accountMapper.incrementFailNum(recipient.getTaskAccountId(), now);
                    accountMapper.stopAccount(recipient.getTaskAccountId(), reasonCode, now);
                    recipientMapper.skipPendingByAccount(recipient.getTaskAccountId(), reasonCode, now);
                    rejectedCount++;
                }
            }
        }
        return rejectedCount;
    }

}
