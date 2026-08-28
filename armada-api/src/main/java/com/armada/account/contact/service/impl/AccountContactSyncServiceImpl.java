package com.armada.account.contact.service.impl;

import com.armada.account.contact.config.AccountContactProperties;
import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.mapper.AccountContactSyncMapper;
import com.armada.account.contact.model.AccountContactSyncResult;
import com.armada.account.contact.model.ContactSyncSource;
import com.armada.account.contact.model.NormalizedContacts;
import com.armada.account.contact.model.entity.AccountContact;
import com.armada.account.contact.model.entity.AccountContactSync;
import com.armada.account.contact.service.AccountContactNormalizer;
import com.armada.account.contact.service.AccountContactSyncService;
import com.armada.account.contact.service.ContactSnapshotFreshness;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;
import com.armada.platform.protocol.port.ContactListPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 账号通讯录采集服务实现。
 *
 * <p>整批替换语义：先 upsert 本批号码，再删除 synced_at 早于本批的残留行。
 * 协议拉取失败时<b>不动任何已有数据</b>，只把同步状态标为 FAILED，
 * 保证「拉不到」不会退化成「通讯录被清空」。</p>
 *
 * <p><b>有意的失败语义</b>：失败时 last_synced_at 写为 NULL，也就是会抹掉上次成功的时间戳，
 * 下一次 syncIfStale 必定重拉。这是刻意选择——拉不到通讯录时任务本就不该拿旧快照发送。
 * 不要把它当 bug 改成 COALESCE 保留旧时间戳。</p>
 *
 * <p><b>本类刻意不标注 @Service</b>：构造参数里有 Function 与 Supplier，Spring 无法自动装配，
 * 必须由 AccountContactConfiguration 显式构造。这样做是为了让本类能用纯 Mockito 测试，
 * 不需要起 Spring 上下文。</p>
 */
public class AccountContactSyncServiceImpl implements AccountContactSyncService {

    private static final Logger log = LoggerFactory.getLogger(AccountContactSyncServiceImpl.class);
    private static final int UPSERT_BATCH_SIZE = 500;
    private static final int FAIL_REASON_MAX = 255;

    private final ContactListPort contactListPort;
    private final AccountContactMapper contactMapper;
    private final AccountContactSyncMapper syncMapper;
    private final AccountStateMapper accountStateMapper;
    private final AccountContactNormalizer normalizer;
    private final AccountContactProperties properties;
    private final Function<Long, ProtocolAccountRef> accountRefResolver;
    private final Supplier<Long> tenantSupplier;
    private final LongSupplier clock;

    /**
     * 创建通讯录采集服务。
     *
     * @param contactListPort 通讯录读取协议端口
     * @param contactMapper 联系人快照数据访问
     * @param syncMapper 同步状态数据访问
     * @param accountStateMapper 账号状态数据访问，用于回写计数
     * @param normalizer 协议快照归一化器
     * @param properties 通讯录采集配置
     * @param accountRefResolver 账号 ID 到协议账号引用的解析器
     * @param tenantSupplier 当前租户提供者
     * @param clock 当前时间提供者（epoch 毫秒）
     */
    public AccountContactSyncServiceImpl(
            ContactListPort contactListPort,
            AccountContactMapper contactMapper,
            AccountContactSyncMapper syncMapper,
            AccountStateMapper accountStateMapper,
            AccountContactNormalizer normalizer,
            AccountContactProperties properties,
            Function<Long, ProtocolAccountRef> accountRefResolver,
            Supplier<Long> tenantSupplier,
            LongSupplier clock) {
        this.contactListPort = contactListPort;
        this.contactMapper = contactMapper;
        this.syncMapper = syncMapper;
        this.accountStateMapper = accountStateMapper;
        this.normalizer = normalizer;
        this.properties = properties;
        this.accountRefResolver = accountRefResolver;
        this.tenantSupplier = tenantSupplier;
        this.clock = clock;
    }

    @Override
    public AccountContactSyncResult syncNow(Long accountId, ContactSyncSource source) {
        long now = clock.getAsLong();
        Long tenantId = tenantSupplier.get();
        AccountContactSnapshot snapshot;
        try {
            snapshot = contactListPort.list(accountRefResolver.apply(accountId));
        } catch (RuntimeException ex) {
            String reason = truncate(
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            log.warn("账号通讯录同步失败,保留既有快照 tenantId={} accountId={} source={} reason={}",
                    tenantId, accountId, source, reason);
            saveSyncState(tenantId, accountId, source, now, null, NormalizedContacts.EMPTY,
                    AccountContactSync.STATUS_FAILED, reason);
            return new AccountContactSyncResult(true, false, 0, 0, 0, null, reason);
        }

        NormalizedContacts normalized = normalizer.normalize(snapshot);
        writeSnapshot(tenantId, accountId, now, normalized);
        accountStateMapper.updateContactCounts(
                accountId, normalized.namedNum(), normalized.mutualNum(), now);
        saveSyncState(tenantId, accountId, source, now, now, normalized,
                AccountContactSync.STATUS_SUCCESS, null);

        log.info("账号通讯录同步成功 tenantId={} accountId={} source={} contactNum={} namedNum={}",
                tenantId, accountId, source, normalized.contactNum(), normalized.namedNum());
        return new AccountContactSyncResult(true, true,
                normalized.contactNum(), normalized.namedNum(), normalized.mutualNum(), now, null);
    }

    @Override
    public AccountContactSyncResult syncIfStale(Long accountId, ContactSyncSource source) {
        AccountContactSync existing = syncMapper.selectByAccountId(accountId);
        long now = clock.getAsLong();
        Long lastSyncedAt = existing == null ? null : existing.getLastSyncedAt();
        if (!ContactSnapshotFreshness.isStale(
                lastSyncedAt, now, properties.snapshotTtlHoursOrDefault())) {
            return new AccountContactSyncResult(
                    false, true,
                    orZero(existing.getContactNum()),
                    orZero(existing.getNamedNum()),
                    orZero(existing.getMutualNum()),
                    lastSyncedAt, null);
        }
        return syncNow(accountId, source);
    }

    /** 整批替换：分批 upsert 后扫掉早于本批的残留行。空批次只扫尾。 */
    private void writeSnapshot(
            Long tenantId, Long accountId, long now, NormalizedContacts normalized) {
        List<AccountContact> batch = new ArrayList<>(UPSERT_BATCH_SIZE);
        for (NormalizedContacts.Row row : normalized.rows()) {
            batch.add(toEntity(tenantId, accountId, now, row));
            if (batch.size() == UPSERT_BATCH_SIZE) {
                contactMapper.upsertBatch(batch);
                batch = new ArrayList<>(UPSERT_BATCH_SIZE);
            }
        }
        if (!batch.isEmpty()) {
            contactMapper.upsertBatch(batch);
        }
        contactMapper.deleteStale(accountId, now);
    }

    private static AccountContact toEntity(
            Long tenantId, Long accountId, long now, NormalizedContacts.Row row) {
        AccountContact entity = new AccountContact();
        entity.setTenantId(tenantId);
        entity.setAccountId(accountId);
        entity.setContactPhone(row.phone());
        entity.setContactJid(row.jid());
        entity.setFullName(row.fullName());
        entity.setFirstName(row.firstName());
        entity.setPushName(row.pushName());
        entity.setBusinessName(row.businessName());
        entity.setIsNamed(row.named() ? 1 : 0);
        entity.setIsMutual(row.mutual() ? 1 : 0);
        entity.setSyncedAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private void saveSyncState(
            Long tenantId, Long accountId, ContactSyncSource source, long now,
            Long lastSyncedAt, NormalizedContacts normalized, String status, String failReason) {
        AccountContactSync row = new AccountContactSync();
        row.setTenantId(tenantId);
        row.setAccountId(accountId);
        row.setLastSyncedAt(lastSyncedAt);
        row.setLastSyncSource(source == null ? null : source.name());
        row.setContactNum(normalized.contactNum());
        row.setNamedNum(normalized.namedNum());
        row.setMutualNum(normalized.mutualNum());
        row.setSyncStatus(status);
        row.setFailReason(failReason);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        syncMapper.upsert(row);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= FAIL_REASON_MAX ? value : value.substring(0, FAIL_REASON_MAX);
    }
}
