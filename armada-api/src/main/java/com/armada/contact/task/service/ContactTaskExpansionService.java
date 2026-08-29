package com.armada.contact.task.service;

import com.armada.account.contact.config.AccountContactProperties;
import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.mapper.AccountContactSyncMapper;
import com.armada.account.contact.model.entity.AccountContact;
import com.armada.account.contact.model.entity.AccountContactSync;
import com.armada.account.contact.service.ContactSnapshotFreshness;
import com.armada.account.selection.AccountFilterSelector;
import com.armada.account.selection.model.SelectedAccount;
import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.entity.ContactFriendTaskAccount;
import com.armada.contact.task.model.entity.ContactFriendTaskRecipient;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 通讯录营销任务启用时的圈号与收件人展开。
 *
 * <p>展开是<b>一次性</b>的：启用时把每个命中账号当前通讯录里有名字的联系人固化成
 * {@code contact_friend_task_recipient} 快照，之后每一轮只是把 PENDING 排干。
 * 通讯录后续变化不回灌已展开的任务——任务事实不跟着主数据漂（超链一期 §6.6）。</p>
 *
 * <p><b>本类刻意不标注 {@code @Service}</b>：构造参数含 Supplier，Spring 无法自动装配，
 * 由 {@code ContactTaskConfiguration} 显式构造，以便纯 Mockito 测试。</p>
 */
public class ContactTaskExpansionService {

    private static final Logger log = LoggerFactory.getLogger(ContactTaskExpansionService.class);

    /** {@code max_sends_per_account = 0} 表示不截断，仍需一个物理上限兜底。 */
    static final int NO_CAP_LIMIT = 100_000;

    /** 单次收件人批量插入条数。 */
    static final int EXPAND_BATCH_SIZE = 500;

    /** 账号状态快照：本任务里这个号能发。 */
    private static final String ACCOUNT_STATUS_VALID = "valid";

    /** 账号状态快照：本任务里这个号发不了。 */
    private static final String ACCOUNT_STATUS_INVALID = "invalid";

    private final AccountFilterSelector selector;
    private final AccountContactSyncMapper syncMapper;
    private final AccountContactProperties properties;
    private final AccountContactMapper contactMapper;
    private final ContactFriendTaskMapper taskMapper;
    private final ContactFriendTaskAccountMapper accountMapper;
    private final ContactFriendTaskRecipientMapper recipientMapper;
    private final LongSupplier clock;
    private final Supplier<Long> tenantSupplier;

    /**
     * 创建展开服务。
     *
     * @param selector 账号圈选服务
     * @param syncMapper 通讯录同步状态数据访问
     * @param properties 通讯录采集配置，提供快照有效期
     * @param contactMapper 通讯录快照数据访问
     * @param taskMapper 任务主表数据访问
     * @param accountMapper 任务账号读模型数据访问
     * @param recipientMapper 收件人明细数据访问
     * @param clock 当前时间提供者（epoch 毫秒）
     * @param tenantSupplier 当前租户提供者
     */
    public ContactTaskExpansionService(AccountFilterSelector selector,
                                       AccountContactSyncMapper syncMapper,
                                       AccountContactProperties properties,
                                       AccountContactMapper contactMapper,
                                       ContactFriendTaskMapper taskMapper,
                                       ContactFriendTaskAccountMapper accountMapper,
                                       ContactFriendTaskRecipientMapper recipientMapper,
                                       LongSupplier clock,
                                       Supplier<Long> tenantSupplier) {
        this.selector = selector;
        this.syncMapper = syncMapper;
        this.properties = properties;
        this.contactMapper = contactMapper;
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.recipientMapper = recipientMapper;
        this.clock = clock;
        this.tenantSupplier = tenantSupplier;
    }

    /**
     * 展开一个任务的账号与收件人。
     *
     * @param task 已通过表单校验并落库的任务
     * @return 展开结果
     * @throws BusinessException 筛选条件命中 0 个可用账号时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public ExpansionResult expand(ContactFriendTask task) {
        int accountLimit = task.getConcurrency() == null || task.getConcurrency() < 1
                ? 1
                : task.getConcurrency();
        List<SelectedAccount> accounts = selector.select(task.getAccountFilter(), accountLimit);
        if (accounts.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION, "账号范围内没有可用账号，无法启用任务");
        }
        long now = clock.getAsLong();
        int usedAccountCount = 0;
        int totalSendNum = 0;
        for (SelectedAccount account : accounts) {
            int expanded = expandOneAccount(task, account, now);
            if (expanded > 0) {
                usedAccountCount++;
                totalSendNum += expanded;
            }
        }
        taskMapper.applyExpansionTotals(task.getId(), totalSendNum, usedAccountCount, now);
        log.info("通讯录任务展开完成 tenantId={} taskId={} selectedAccounts={} usedAccounts={} recipients={}",
                task.getTenantId(), task.getId(), accounts.size(), usedAccountCount, totalSendNum);
        return new ExpansionResult(usedAccountCount, totalSendNum);
    }

    /** 展开单个账号，返回该账号实际展开的收件人条数；跳过时返回 0。 */
    private int expandOneAccount(ContactFriendTask task, SelectedAccount account, long now) {
        // 通讯录由协议层周期推送，armada 不再主动拉；这里只读当前快照的新鲜度。
        AccountContactSync sync = syncMapper.selectByAccountId(account.accountId());
        Long lastSyncedAt = sync == null ? null : sync.getLastSyncedAt();
        if (ContactSnapshotFreshness.isStale(
                lastSyncedAt, now, properties.snapshotTtlHoursOrDefault())) {
            // 快照缺失或过期：宁可少发，也不拿陈数据发。
            // 号下次上线协议会自动推，下一个任务就能用。isStale(null, ..) 已返回 true，
            // 因此缺失与过期是同一条分支。
            insertAccountRow(task, account, 0, lastSyncedAt,
                    ContactFriendTaskAccount.STATE_SKIPPED, now);
            log.info("通讯录任务跳过快照不可用账号 taskId={} accountId={} lastSyncedAt={} status={}",
                    task.getId(), account.accountId(), lastSyncedAt,
                    sync == null ? "NONE" : sync.getSyncStatus());
            return 0;
        }
        int cap = task.getMaxSendsPerAccount() == null || task.getMaxSendsPerAccount() <= 0
                ? NO_CAP_LIMIT
                : task.getMaxSendsPerAccount();
        List<AccountContact> contacts =
                contactMapper.selectNamedByAccount(account.accountId(), cap);
        if (contacts == null || contacts.isEmpty()) {
            insertAccountRow(task, account, 0, lastSyncedAt,
                    ContactFriendTaskAccount.STATE_SKIPPED, now);
            return 0;
        }
        ContactFriendTaskAccount accountRow = insertAccountRow(
                task, account, contacts.size(), lastSyncedAt,
                ContactFriendTaskAccount.STATE_PENDING, now);
        insertRecipients(task, accountRow.getId(), contacts, now);
        return contacts.size();
    }

    private ContactFriendTaskAccount insertAccountRow(ContactFriendTask task,
                                                      SelectedAccount account,
                                                      int needSendNum,
                                                      Long contactSyncedAt,
                                                      String state,
                                                      long now) {
        ContactFriendTaskAccount row = new ContactFriendTaskAccount();
        row.setTenantId(tenantSupplier.get());
        row.setTaskId(task.getId());
        row.setAccountId(account.accountId());
        row.setAccountPhoneSnapshot(account.wsPhone());
        row.setAccountStatusSnapshot(
                needSendNum > 0 ? ACCOUNT_STATUS_VALID : ACCOUNT_STATUS_INVALID);
        row.setNeedSendNum(needSendNum);
        row.setState(state);
        row.setContactSyncedAt(contactSyncedAt);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        accountMapper.insert(row);
        return row;
    }

    /** 分批插入收件人；空批次绝不下发（{@code foreach} 会生成空 VALUES 语法错）。 */
    private void insertRecipients(ContactFriendTask task,
                                  Long taskAccountId,
                                  List<AccountContact> contacts,
                                  long now) {
        List<ContactFriendTaskRecipient> batch = new ArrayList<>(EXPAND_BATCH_SIZE);
        for (AccountContact contact : contacts) {
            ContactFriendTaskRecipient row = new ContactFriendTaskRecipient();
            row.setTenantId(tenantSupplier.get());
            row.setTaskId(task.getId());
            row.setTaskAccountId(taskAccountId);
            row.setContactPhone(contact.getContactPhone());
            row.setContactJid(contact.getContactJid());
            row.setContactNamed(contact.getIsNamed() == null ? 0 : contact.getIsNamed());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            batch.add(row);
            if (batch.size() == EXPAND_BATCH_SIZE) {
                recipientMapper.insertBatch(batch);
                batch = new ArrayList<>(EXPAND_BATCH_SIZE);
            }
        }
        if (!batch.isEmpty()) {
            recipientMapper.insertBatch(batch);
        }
    }

    /**
     * 展开结果。
     *
     * @param accountCount 实际参与发送的账号数（need_send_num 大于 0）
     * @param recipientCount 展开出的收件人总条数
     */
    public record ExpansionResult(int accountCount, int recipientCount) {
    }
}
