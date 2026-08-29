package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskSaveDTO;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskContent;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.hyperlink.task.model.enums.HyperlinkProvisionStatus;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskStartMode;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskMutationReceiptVO;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort.Action;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort.AuditEvent;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 创建、编辑与准备状态查询；动作状态机由独立服务承担。 */
@Service
public class HyperlinkTaskLifecycleService {
    private final HyperlinkTaskConfigurationFactory configurationFactory;
    private final HyperlinkTaskStoreService store;
    private final HyperlinkTaskQuoteGuardService quoteGuard;
    private final HyperlinkProvisionFactService provisionFactService;
    private final HyperlinkCleanupStartService cleanupStartService;
    private final HyperlinkTaskRoundMapper roundMapper;
    private final HyperlinkTaskAuditPort auditPort;
    private final HyperlinkShortLinkGuard shortLinkGuard;
    private final HyperlinkProtocolCapacityService capacityService;

    public HyperlinkTaskLifecycleService(HyperlinkTaskConfigurationFactory configurationFactory,
            HyperlinkTaskStoreService store, HyperlinkTaskQuoteGuardService quoteGuard,
            HyperlinkProvisionFactService provisionFactService,
            HyperlinkCleanupStartService cleanupStartService, HyperlinkTaskRoundMapper roundMapper,
            HyperlinkTaskAuditPort auditPort, HyperlinkShortLinkGuard shortLinkGuard,
            HyperlinkProtocolCapacityService capacityService) {
        this.configurationFactory = configurationFactory;
        this.store = store;
        this.quoteGuard = quoteGuard;
        this.provisionFactService = provisionFactService;
        this.cleanupStartService = cleanupStartService;
        this.roundMapper = roundMapper;
        this.auditPort = auditPort;
        this.shortLinkGuard = shortLinkGuard;
        this.capacityService = capacityService;
    }

    @Transactional(rollbackFor = Exception.class)
    public HyperlinkTaskMutationReceiptVO create(HyperlinkTaskSaveDTO request, AuthPrincipal principal) {
        HyperlinkTaskConfigurationFactory.Normalized normalized =
                configurationFactory.normalizeForCreate(request);
        if (request.version() != null) { throw validation("创建 version 必须为 null"); }
        if (request.sourceTaskId() != null) { store.requireTask(request.sourceTaskId()); }
        if (normalized.enabled()) {
            capacityService.requireSufficient(normalized.maxExecutingAccounts());
        }
        long now = System.currentTimeMillis();
        HyperlinkQuoteTokenService.QuoteClaims claims = normalized.enabled()
                ? quoteGuard.forCreate(request, principal, now) : null;
        HyperlinkTask task = configurationFactory.task(request, normalized, principal.userId(), now);
        configurationFactory.applyPackageSnapshot(task, claims);
        HyperlinkTaskRuntime runtime = configurationFactory.runtime(0, normalized.enabled(), now);
        if (normalized.enabled()) { shortLinkGuard.requireConfigured(task.getShortLinkEnabled()); }
        auditPort.requireAvailable();
        store.insert(task, configurationFactory.content(0, normalized.content(), now), runtime);
        if (normalized.enabled()) { provisionFactService.prepare(task, claims, now); }
        auditPort.record(new AuditEvent("hyperlink-task:create:" + task.getId(), Action.CREATE,
                principal.tenantId(), principal.userId(), task.getId(), now));
        return store.receipt(task, runtime);
    }

    /** 未开始任务可编辑；冻结范围变化走重建，已准备首轮的启动时间在本事务内条件重排。 */
    @Transactional(rollbackFor = Exception.class)
    public HyperlinkTaskMutationReceiptVO update(long taskId, HyperlinkTaskSaveDTO request,
            AuthPrincipal principal) {
        if (request.version() == null || request.sourceTaskId() != null) {
            throw validation("更新必须携带 version 且 sourceTaskId 必须为 null");
        }
        HyperlinkTask existing = store.requireTask(taskId);
        HyperlinkTaskContent existingContent = store.requireContent(taskId);
        HyperlinkTaskRuntime runtime = store.requireRuntime(taskId);
        HyperlinkTaskConfigurationFactory.Normalized normalized =
                configurationFactory.normalizeForUpdate(request, existingContent.getMessageType());
        if (runtime.getRunStatus() != 0 || runtime.getProvisionStatus() == 1) { throw stateConflict(); }
        cleanupStartService.requireNoCommandedRecipient(taskId);
        if (normalized.enabled()) {
            capacityService.requireSufficient(normalized.maxExecutingAccounts());
        }
        long now = System.currentTimeMillis();
        HyperlinkQuoteTokenService.QuoteClaims claims = normalized.enabled()
                ? quoteGuard.internalForSave(request, principal) : null;
        HyperlinkTask replacement = configurationFactory.task(request, normalized,
                existing.getCreatedBy(), now);
        replacement.setId(taskId);
        configurationFactory.applyPackageSnapshot(replacement, claims);
        boolean enabledChanged = normalized.enabled() != Boolean.TRUE.equals(runtime.getEnabled());
        boolean frozenChanged = configurationFactory.frozenScopeChanged(existing, replacement);
        boolean scheduleChanged = !java.util.Objects.equals(existing.getStartMode(),
                replacement.getStartMode()) || !java.util.Objects.equals(
                existing.getTaskDelayMinutes(), replacement.getTaskDelayMinutes());
        if (normalized.enabled()) {
            shortLinkGuard.requireConfigured(replacement.getShortLinkEnabled());
        }
        auditPort.requireAvailable();
        store.update(replacement, configurationFactory.content(taskId, normalized.content(), now),
                request.version());
        replacement.setVersion(request.version() + 1);

        if (Boolean.TRUE.equals(runtime.getEnabled()) && (enabledChanged || frozenChanged)) {
            if (!store.beginRebuild(taskId, normalized.enabled(), now)) { throw stateConflict(); }
            cleanupStartService.begin(taskId, !normalized.enabled(), now);
        } else if (normalized.enabled() && !Boolean.TRUE.equals(runtime.getEnabled())) {
            if (!store.transition(taskId, false, 0, true, 0, 1, now)) { throw stateConflict(); }
            provisionFactService.prepare(replacement, claims, now);
        } else if (scheduleChanged && normalized.enabled()
                && runtime.getProvisionStatus() == HyperlinkProvisionStatus.READY.code()) {
            long scheduledAt = normalized.startMode() == HyperlinkTaskStartMode.NOW
                    ? now : now + normalized.delayMinutes() * 60_000L;
            if (roundMapper.rescheduleUnconsumedFirstRound(taskId, scheduledAt, now) != 1) {
                throw stateConflict();
            }
        }
        auditPort.record(new AuditEvent("hyperlink-task:update:" + taskId + ":version:"
                + replacement.getVersion(), Action.UPDATE, principal.tenantId(),
                principal.userId(), taskId, now));
        return store.receipt(taskId);
    }

    public HyperlinkTaskMutationReceiptVO provisionStatus(long taskId) {
        return store.receipt(taskId);
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private BusinessException stateConflict() {
        return new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT);
    }
}
