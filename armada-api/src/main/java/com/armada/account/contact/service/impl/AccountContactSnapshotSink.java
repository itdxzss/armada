package com.armada.account.contact.service.impl;

import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.mapper.AccountContactSyncMapper;
import com.armada.account.contact.model.NormalizedContacts;
import com.armada.account.contact.model.entity.AccountContact;
import com.armada.account.contact.model.entity.AccountContactSync;
import com.armada.account.contact.service.AccountContactNormalizer;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.platform.kafka.consumer.contact.AccountContactsReportedEvent;
import com.armada.platform.kafka.consumer.contact.AccountContactsReportedSink;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;
import com.armada.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 协议通讯录快照分片落库。
 *
 * <p><b>收齐判据不是「收到最后一片」</b>：Kafka 不保证分片顺序，末片先到时按末片触发
 * 会永远不再有触发点，陈数据永久滞留。因此每片落库后都精确统计「本快照已落库条数」，
 * 等于 totalCount 才算收齐。</p>
 *
 * <p><b>删除只在两个条件同时满足时发生</b>：本快照收齐，且协议层判定快照完整。
 * 丢片或协议判定不完整时宁可留脏数据 —— 半份快照会把号主的通讯录删掉一半。</p>
 *
 * <p><b>本类刻意不标注 @Service</b>：构造参数里有 LongSupplier，Spring 无法自动装配，
 * 由 AccountContactConfiguration 显式构造，从而能用纯 Mockito 测试。</p>
 */
public class AccountContactSnapshotSink implements AccountContactsReportedSink {

    private static final Logger log = LoggerFactory.getLogger(AccountContactSnapshotSink.class);
    private static final int UPSERT_BATCH_SIZE = 500;

    /** 快照来源标识，写进 account_contact_sync.last_sync_source。 */
    private static final String SYNC_SOURCE = "PROTOCOL_SNAPSHOT";

    /** 双向好友标记两套协议都不暴露（设计 §5.3 待验证项 V2），恒为 0。 */
    private static final int MUTUAL_NUM = 0;

    private final AccountContactMapper contactMapper;
    private final AccountContactSyncMapper syncMapper;
    private final AccountStateMapper accountStateMapper;
    private final AccountContactNormalizer normalizer;
    private final LongSupplier clock;

    /**
     * @param contactMapper 联系人快照数据访问
     * @param syncMapper 同步状态数据访问
     * @param accountStateMapper 账号状态数据访问，用于回写计数
     * @param normalizer 协议快照归一化器
     * @param clock 当前时间提供者（epoch 毫秒），只用于行的 created_at/updated_at
     */
    public AccountContactSnapshotSink(
            AccountContactMapper contactMapper,
            AccountContactSyncMapper syncMapper,
            AccountStateMapper accountStateMapper,
            AccountContactNormalizer normalizer,
            LongSupplier clock) {
        this.contactMapper = contactMapper;
        this.syncMapper = syncMapper;
        this.accountStateMapper = accountStateMapper;
        this.normalizer = normalizer;
        this.clock = clock;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handle(AccountContactsReportedEvent event) {
        Long previous = TenantContext.get();
        // 事件从 Kafka 线程进来，没有 HTTP 请求带过来的租户，必须由事件自己声明。
        TenantContext.set(event.tenantId());
        try {
            long syncedAt = event.snapshotCutoff();
            NormalizedContacts normalized = normalizer.normalize(toSnapshot(event));
            if (!normalized.rows().isEmpty()) {
                upsertInBatches(event, syncedAt, normalized);
            }
            int landed = contactMapper.countBySyncedAt(event.accountId(), syncedAt);
            if (landed < event.totalCount()) {
                // 分片没收齐：入库但不删残留、不回写计数。半路回写会让账号筛选读到偏小的好友数。
                saveSyncState(event, landed, 0, AccountContactSync.STATUS_SYNCING, null);
                return;
            }
            int namedNum = contactMapper.countNamedBySyncedAt(event.accountId(), syncedAt);
            if (!event.snapshotComplete()) {
                // 协议自己判定不完整（强制 resync 中途超时）：入库但不清残留。
                saveSyncState(event, landed, namedNum, AccountContactSync.STATUS_PARTIAL,
                        "protocol reported incomplete snapshot");
                log.warn("通讯录快照不完整,保留残留行 tenantId={} accountId={} snapshotId={} landed={}",
                        event.tenantId(), event.accountId(), event.snapshotId(), landed);
                return;
            }
            int removed = contactMapper.deleteStale(event.accountId(), syncedAt);
            accountStateMapper.updateContactCounts(
                    event.accountId(), namedNum, MUTUAL_NUM, clock.getAsLong());
            saveSyncState(event, landed, namedNum, AccountContactSync.STATUS_SUCCESS, null);
            log.info("通讯录快照落库完成 tenantId={} accountId={} snapshotId={} contactNum={} "
                            + "namedNum={} removedStale={}",
                    event.tenantId(), event.accountId(), event.snapshotId(), landed, namedNum, removed);
        } finally {
            restoreTenant(previous);
        }
    }

    /** 事件形状转成归一化器的输入形状；归一化逻辑与拉取路径共用一处。 */
    private static AccountContactSnapshot toSnapshot(AccountContactsReportedEvent event) {
        List<AccountContactSnapshot.Contact> contacts = new ArrayList<>(event.contacts().size());
        for (AccountContactsReportedEvent.ReportedContact contact : event.contacts()) {
            contacts.add(new AccountContactSnapshot.Contact(
                    contact.phone(),
                    contact.jid(),
                    contact.fullName(),
                    contact.firstName(),
                    contact.pushName(),
                    contact.businessName()));
        }
        return new AccountContactSnapshot(contacts, event.snapshotCutoff());
    }

    /** 空批次不得调 upsertBatch：foreach 会生成空 VALUES 导致语法错。 */
    private void upsertInBatches(
            AccountContactsReportedEvent event, long syncedAt, NormalizedContacts normalized) {
        long now = clock.getAsLong();
        List<AccountContact> batch = new ArrayList<>(UPSERT_BATCH_SIZE);
        for (NormalizedContacts.Row row : normalized.rows()) {
            batch.add(toEntity(event, syncedAt, now, row));
            if (batch.size() == UPSERT_BATCH_SIZE) {
                contactMapper.upsertBatch(batch);
                batch = new ArrayList<>(UPSERT_BATCH_SIZE);
            }
        }
        if (!batch.isEmpty()) {
            contactMapper.upsertBatch(batch);
        }
    }

    private static AccountContact toEntity(
            AccountContactsReportedEvent event, long syncedAt, long now, NormalizedContacts.Row row) {
        AccountContact entity = new AccountContact();
        entity.setTenantId(event.tenantId());
        entity.setAccountId(event.accountId());
        entity.setContactPhone(row.phone());
        entity.setContactJid(row.jid());
        entity.setFullName(row.fullName());
        entity.setFirstName(row.firstName());
        entity.setPushName(row.pushName());
        entity.setBusinessName(row.businessName());
        entity.setIsNamed(row.named() ? 1 : 0);
        entity.setIsMutual(row.mutual() ? 1 : 0);
        // synced_at 用协议给的快照截止时间，不是 armada 的 now —— 这是整条链路的目的。
        entity.setSyncedAt(syncedAt);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private void saveSyncState(
            AccountContactsReportedEvent event, int contactNum, int namedNum,
            String status, String failReason) {
        long now = clock.getAsLong();
        AccountContactSync row = new AccountContactSync();
        row.setTenantId(event.tenantId());
        row.setAccountId(event.accountId());
        // last_synced_at 记的是「数据有多新」，因此写协议给的快照时间。
        row.setLastSyncedAt(event.snapshotCutoff());
        row.setLastSyncSource(SYNC_SOURCE);
        row.setContactNum(contactNum);
        row.setNamedNum(namedNum);
        row.setMutualNum(MUTUAL_NUM);
        row.setSyncStatus(status);
        row.setFailReason(failReason);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        syncMapper.upsert(row);
    }

    private static void restoreTenant(Long previous) {
        if (previous == null) {
            TenantContext.clear();
        } else {
            TenantContext.set(previous);
        }
    }
}
