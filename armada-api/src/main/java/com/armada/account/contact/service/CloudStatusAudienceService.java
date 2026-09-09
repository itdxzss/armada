package com.armada.account.contact.service;

import static com.armada.account.contact.model.entity.AccountStatusAudience.NEVER;
import static com.armada.account.contact.model.entity.AccountStatusAudience.SYNCING;
import static com.armada.account.contact.model.entity.AccountStatusAudience.COMPLETE;
import static com.armada.account.contact.model.entity.AccountStatusAudience.FAILED;

import com.armada.account.contact.mapper.AccountStatusAudienceMapper;
import com.armada.account.contact.model.StatusAudienceResolution;
import com.armada.account.contact.model.StatusAudienceView;
import com.armada.account.contact.model.entity.AccountStatusAudience;
import com.armada.account.selection.model.SelectedAccount;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 在任务事务提交后准备完整快照；代次与租约阻止晚到结果覆盖。 */
@Service
public class CloudStatusAudienceService {
    private static final long LEASE_MS = 300_000L;
    private static final long SNAPSHOT_TTL_MS = 86_400_000L;
    private final AccountStatusAudienceMapper mapper;
    private final CloudStatusAudienceCollector collector;
    private final ObjectMapper json;
    private final Semaphore slots = new Semaphore(4);
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), runnable -> {
                Thread thread = new Thread(runnable, "cloud-status-audience");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public CloudStatusAudienceService(AccountStatusAudienceMapper mapper,
                                     CloudStatusAudienceCollector collector, ObjectMapper json) {
        this.mapper = mapper;
        this.collector = collector;
        this.json = json;
    }

    /** 只在当前租户中申请抓取，不在数据库事务中进行协议请求。 */
    @Transactional(rollbackFor = Exception.class)
    public StatusAudienceResolution resolve(SelectedAccount account, boolean refresh) {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new IllegalStateException("云端受众缺少租户上下文");
        }
        long now = System.currentTimeMillis();
        AccountStatusAudience current = mapper.selectByAccountId(account.accountId());
        StatusAudienceView view = view(current, now);
        if (!refresh && ("READY".equals(view.status()) || "FAILED".equals(view.status()) || "EMPTY".equals(view.status()))) {
            return resolution(current, view);
        }
        if ("SYNCING".equals(view.status()) || !slots.tryAcquire()) {
            return new StatusAudienceResolution(view, List.of());
        }
        boolean registered = false;
        try {
            AccountStatusAudience claim = new AccountStatusAudience();
            claim.setTenantId(tenantId);
            claim.setAccountId(account.accountId());
            claim.setRequestToken(UUID.randomUUID().toString());
            claim.setLeaseUntil(now + LEASE_MS);
            claim.setUpdatedAt(now);
            mapper.ensure(claim);
            if (mapper.claim(claim, current == null ? now : current.getUpdatedAt()) == 0) {
                AccountStatusAudience latest = mapper.selectByAccountId(account.accountId());
                return resolution(latest, view(latest, now));
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        slots.release();
                        return;
                    }
                    try {
                        executor.execute(() -> fetch(account, claim));
                    } catch (RuntimeException rejected) {
                        slots.release(); // 租约到期后由下一轮恢复，不能在提交后的旧事务里写状态。
                    }
                }
            });
            registered = true;
            return new StatusAudienceResolution(new StatusAudienceView("SYNCING", "CLOUD_LID", 0, now, null, null), List.of());
        } finally {
            if (!registered) {
                slots.release();
            }
        }
    }

    private StatusAudienceResolution resolution(AccountStatusAudience row, StatusAudienceView view) {
        if (!"READY".equals(view.status())) {
            return new StatusAudienceResolution(view, List.of());
        }
        try {
            List<String> jids = json.readValue(row.getJidsJson(), new TypeReference<List<String>>() {});
            if (row.getSnapshotVersion() == null || row.getSnapshotVersion().isBlank()
                    || jids.size() != row.getContactNum() || jids.size() > 5000
                    || jids.stream().anyMatch(jid -> jid == null || !jid.matches("[1-9][0-9]{0,19}@lid"))) {
                throw new IllegalArgumentException("invalid snapshot");
            }
            return new StatusAudienceResolution(view, jids);
        } catch (Exception invalid) {
            return new StatusAudienceResolution(new StatusAudienceView("FAILED", "CLOUD_LID", 0,
                    row.getUpdatedAt(), "CLOUD_INVALID_SNAPSHOT", "云端受众快照无效，请重新准备"), List.of());
        }
    }

    private void fetch(SelectedAccount account, AccountStatusAudience row) {
        Long previous = TenantContext.get();
        TenantContext.set(row.getTenantId());
        try {
            try {
                if (System.currentTimeMillis() >= row.getLeaseUntil()) { return; }
                var snapshot = collector.collect(new ProtocolAccountRef(account.accountId(), ProtocolBackend.ANDROID,
                        account.protocolAccountId(), account.wsPhone()));
                row.setJidsJson(json.writeValueAsString(snapshot.jids()));
                row.setContactNum(snapshot.jids().size());
                row.setSnapshotVersion(snapshot.version());
                row.setSyncedAt(System.currentTimeMillis());
                row.setExpiresAt(row.getSyncedAt() + SNAPSHOT_TTL_MS);
                row.setSyncStatus(COMPLETE);
            } catch (Exception failed) {
                row.setSyncStatus(FAILED);
                row.setJidsJson(null);
                row.setContactNum(0);
                String code = failed.getMessage();
                row.setFailCode(code != null && code.matches("CLOUD_[A-Z_]{1,50}") ? code : "CLOUD_FETCH_FAILED");
                row.setFailReason("云端受众准备失败，请确认账号在线后重新准备；不会使用部分结果");
            }
            row.setUpdatedAt(System.currentTimeMillis());
            mapper.finish(row);
        } finally {
            if (previous == null) { TenantContext.clear(); } else { TenantContext.set(previous); }
            slots.release();
        }
    }

    /** 只读页面状态；过期租约显示待准备，不能把旧人数显示为就绪。 */
    public static StatusAudienceView view(AccountStatusAudience row, long now) {
        if (row == null || row.getSyncStatus() == NEVER) {
            return new StatusAudienceView("PENDING", "CLOUD_LID", 0, null, null, null);
        }
        if (row.getSyncStatus() == SYNCING && row.getLeaseUntil() != null && row.getLeaseUntil() > now) {
            return new StatusAudienceView("SYNCING", "CLOUD_LID", 0, row.getUpdatedAt(), null, null);
        }
        if (row.getSyncStatus() == FAILED) {
            return new StatusAudienceView("FAILED", "CLOUD_LID", 0, row.getUpdatedAt(), row.getFailCode(), row.getFailReason());
        }
        if (row.getSyncStatus() == COMPLETE && row.getExpiresAt() != null && row.getExpiresAt() > now) {
            return new StatusAudienceView(row.getContactNum() > 0 ? "READY" : "EMPTY", "CLOUD_LID",
                    row.getContactNum(), row.getUpdatedAt(), row.getContactNum() > 0 ? null : "NO_STATUS_RECIPIENTS",
                    row.getContactNum() > 0 ? null : "云端通讯录没有候选受众");
        }
        return new StatusAudienceView("PENDING", "CLOUD_LID", 0, row.getUpdatedAt(), null, null);
    }

    @PreDestroy
    public void shutdown() { executor.shutdownNow(); }
}
