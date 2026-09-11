package com.armada.contact.task.service;

import com.armada.account.contact.model.StatusAudienceResolution;
import com.armada.account.selection.model.SelectedAccount;
import com.armada.account.service.AccountMessagingAudienceService;
import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.entity.ContactFriendTaskAccount;
import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 在轮次事务内准备 Android 名单，完整结果只固化一次，网络采集由账号域在提交后执行。 */
@Service
public class ContactTaskCloudPreparationService {

    private static final Logger log = LoggerFactory.getLogger(ContactTaskCloudPreparationService.class);
    private static final int INSERT_BATCH_SIZE = 500;
    private static final String NO_SENDABLE_FRIENDS_REASON = "没有可发送好友（已排除账号自身）";
    private final AccountMessagingAudienceService audiences;
    private final ContactFriendTaskMapper tasks;
    private final ContactFriendTaskAccountMapper accounts;
    private final ContactFriendTaskRecipientMapper recipients;

    /** 创建名单准备器，复用账号域采集和任务的账号、收件人存储。 */
    public ContactTaskCloudPreparationService(AccountMessagingAudienceService audiences,
                                             ContactFriendTaskMapper tasks,
                                             ContactFriendTaskAccountMapper accounts,
                                             ContactFriendTaskRecipientMapper recipients) {
        this.audiences = audiences;
        this.tasks = tasks;
        this.accounts = accounts;
        this.recipients = recipients;
    }

    /**
     * 推进本任务所有待准备账号；已固化、失败及旧 SKIPPED 行不会重新展开。
     * @param task 当前租户任务，调用方必须在同一事务内持有该任务行锁
     * @param now 本次轮次时间，毫秒
     */
    public void prepare(ContactFriendTask task, long now) {
        List<ContactFriendTaskAccount> pending = accounts.selectPreparing(task.getId());
        if (pending.isEmpty()) {
            return;
        }
        Map<Long, SelectedAccount> facts = audiences.selectSendableByIds(pending.stream()
                .map(ContactFriendTaskAccount::getAccountId).toList()).stream()
                .collect(Collectors.toMap(SelectedAccount::accountId, Function.identity()));
        for (ContactFriendTaskAccount row : pending) {
            SelectedAccount fact = facts.get(row.getAccountId());
            if (fact == null) {
                finish(row, ContactFriendTaskAccount.STATE_FAILED, "账号当前不可发送，未取得云端 LID 名单", now);
                continue;
            }
            StatusAudienceResolution resolution = audiences.resolveContactAudience(fact, row.getCreatedAt());
            switch (resolution.view().status()) {
                case "READY" -> freeze(task, row, resolution, now);
                case "EMPTY" -> finish(row, ContactFriendTaskAccount.STATE_SKIPPED, NO_SENDABLE_FRIENDS_REASON, now);
                case "FAILED", "UNAVAILABLE" -> finish(row, ContactFriendTaskAccount.STATE_FAILED,
                        resolution.view().failReason() == null ? "云端 LID 名单准备失败" : resolution.view().failReason(), now);
                // 包括采集队列已满时的 PENDING：保持准备中，不产生发送命令或消耗重试。
                case "PENDING", "SYNCING" -> { /* 保持 PREPARING，下一轮读取完整结果。 */ }
                default -> throw new IllegalStateException("未知的云端名单准备状态");
            }
        }
        tasks.refreshExpansionTotals(task.getId(), now);
    }

    private void freeze(ContactFriendTask task, ContactFriendTaskAccount row,
                        StatusAudienceResolution resolution, long now) {
        int cap = task.getMaxSendsPerAccount() == null || task.getMaxSendsPerAccount() <= 0
                ? Integer.MAX_VALUE : task.getMaxSendsPerAccount();
        List<String> targets = resolution.jids().stream().distinct().limit(cap).toList();
        if (targets.isEmpty()) {
            finish(row, ContactFriendTaskAccount.STATE_SKIPPED, NO_SENDABLE_FRIENDS_REASON, now);
            return;
        }
        List<ContactFriendTaskRecipient> batch = new ArrayList<>(INSERT_BATCH_SIZE);
        for (String jid : targets) {
            ContactFriendTaskRecipient recipient = new ContactFriendTaskRecipient();
            recipient.setTenantId(task.getTenantId());
            recipient.setTaskId(task.getId());
            recipient.setTaskAccountId(row.getId());
            recipient.setContactJid(jid);
            recipient.setContactNamed(0);
            recipient.setCreatedAt(now);
            recipient.setUpdatedAt(now);
            batch.add(recipient);
            if (batch.size() == INSERT_BATCH_SIZE) {
                recipients.insertBatch(batch);
                batch = new ArrayList<>(INSERT_BATCH_SIZE);
            }
        }
        if (!batch.isEmpty()) {
            recipients.insertBatch(batch);
        }
        row.setNeedSendNum(targets.size());
        row.setContactSyncedAt(resolution.view().updatedAt());
        finish(row, ContactFriendTaskAccount.STATE_PENDING, null, now);
    }

    private void finish(ContactFriendTaskAccount row, String state, String reason, long now) {
        row.setState(state);
        row.setNeedSendNum(row.getNeedSendNum() == null ? 0 : row.getNeedSendNum());
        row.setAccountStatusSnapshot(row.getNeedSendNum() > 0 ? "valid" : "invalid");
        row.setStopReason(reason == null ? null : reason.substring(0, Math.min(255, reason.length())));
        row.setUpdatedAt(now);
        if (accounts.finishPreparation(row) != 1) {
            throw new IllegalStateException("通讯录任务名单准备状态已改变");
        }
        log.info("通讯录任务云端名单准备完成 tenantId={} taskId={} taskAccountId={} state={} recipients={}",
                row.getTenantId(), row.getTaskId(), row.getId(), state, row.getNeedSendNum());
    }
}
