package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskActionDTO;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.hyperlink.task.model.enums.HyperlinkProvisionStatus;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskAction;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskRunStatus;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskMutationReceiptVO;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort.AuditEvent;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** START/PAUSE/RESUME/STOP 的短事务编排。 */
@Service
public class HyperlinkTaskActionService {
    private final HyperlinkTaskStoreService store;
    private final HyperlinkTaskStateMachine stateMachine = new HyperlinkTaskStateMachine();
    private final HyperlinkTaskQuoteGuardService quoteGuard;
    private final HyperlinkProvisionFactService provisionFactService;
    private final HyperlinkTaskRoundMapper roundMapper;
    private final HyperlinkCleanupStartService cleanupStartService;
    private final HyperlinkTaskAuditPort auditPort;
    private final HyperlinkShortLinkGuard shortLinkGuard;
    private final HyperlinkProtocolCapacityService capacityService;

    public HyperlinkTaskActionService(HyperlinkTaskStoreService store,
            HyperlinkTaskQuoteGuardService quoteGuard,
            HyperlinkProvisionFactService provisionFactService, HyperlinkTaskRoundMapper roundMapper,
            HyperlinkCleanupStartService cleanupStartService, HyperlinkTaskAuditPort auditPort,
            HyperlinkShortLinkGuard shortLinkGuard,
            HyperlinkProtocolCapacityService capacityService) {
        this.store = store;
        this.quoteGuard = quoteGuard;
        this.provisionFactService = provisionFactService;
        this.roundMapper = roundMapper;
        this.cleanupStartService = cleanupStartService;
        this.auditPort = auditPort;
        this.shortLinkGuard = shortLinkGuard;
        this.capacityService = capacityService;
    }

    @Transactional(rollbackFor = Exception.class)
    public HyperlinkTaskMutationReceiptVO action(long taskId, HyperlinkTaskActionDTO request,
            AuthPrincipal principal) {
        if (request == null || request.action() == null || request.version() == null) {
            throw validation("action 和 version 必填");
        }
        if (request.action() != HyperlinkTaskAction.START && request.quoteToken() != null) {
            throw validation("仅 START 允许携带 quoteToken");
        }
        HyperlinkTask task = store.requireTask(taskId);
        HyperlinkTaskRuntime runtime = store.requireRuntime(taskId);
        HyperlinkTaskRunStatus current = HyperlinkTaskRunStatus.fromCode(runtime.getRunStatus());
        HyperlinkTaskRunStatus next = stateMachine.next(Boolean.TRUE.equals(runtime.getEnabled()),
                current, request.action());
        long now = System.currentTimeMillis();
        if (request.action() == HyperlinkTaskAction.START) {
            capacityService.requireSufficient(task.getConcurrentNum());
            HyperlinkQuoteTokenService.QuoteClaims claims = quoteGuard.forStart(request.quoteToken(),
                    taskId, request.version(), task, principal, now);
            shortLinkGuard.requireConfigured(task.getShortLinkEnabled());
            HyperlinkMessageDeliveryGuard.requireSupported(store.requireContent(taskId));
            auditPort.requireAvailable();
            store.incrementVersion(taskId, request.version(), now);
            if (!Boolean.TRUE.equals(runtime.getEnabled())) {
                if (!store.transition(taskId, false, 0, true, 0, 1, now)) { throw stateConflict(); }
                task.setVersion(request.version() + 1);
                provisionFactService.prepare(task, claims, now);
            } else if (runtime.getProvisionStatus() == HyperlinkProvisionStatus.FAILED.code()) {
                if (Integer.valueOf(ErrorCode.HYPERLINK_QUOTE_STALE.code())
                        .equals(runtime.getFailureCode())) {
                    task.setVersion(request.version() + 1);
                    provisionFactService.replaceFailedOwnedQuote(task, claims, now);
                    if (!store.resumeProvisioning(taskId, now)) { throw stateConflict(); }
                } else if (!store.resumeProvisioning(taskId, now)) {
                    throw stateConflict();
                }
            } else if (runtime.getProvisionStatus() == HyperlinkProvisionStatus.READY.code()) {
                roundMapper.scheduleNow(taskId, now);
            } else {
                throw stateConflict();
            }
        } else {
            auditPort.requireAvailable();
            store.incrementVersion(taskId, request.version(), now);
            if (!store.transition(taskId, true, current.code(), true, next.code(),
                    runtime.getProvisionStatus(), now)) {
                throw stateConflict();
            }
            if (request.action() == HyperlinkTaskAction.STOP) {
                cleanupStartService.begin(taskId, true, now);
            } else if (request.action() == HyperlinkTaskAction.PAUSE) {
                roundMapper.pauseActive(taskId, now);
            } else if (request.action() == HyperlinkTaskAction.RESUME) {
                roundMapper.resumePaused(taskId, now);
            }
        }
        auditPort.record(new AuditEvent("hyperlink-task:action:" + taskId + ":"
                + request.action().name() + ":version:" + request.version(),
                HyperlinkTaskAuditPort.Action.valueOf(request.action().name()),
                principal.tenantId(), principal.userId(), taskId, now));
        HyperlinkTaskMutationReceiptVO receipt = store.receipt(taskId);
        if (request.action() == HyperlinkTaskAction.START) { return receipt; }
        return new HyperlinkTaskMutationReceiptVO(receipt.taskId(),
                HyperlinkProvisionStatus.NOT_REQUIRED, receipt.enabled(), receipt.runStatus(),
                receipt.version(), null, null, null);
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }

    private BusinessException stateConflict() {
        return new BusinessException(ErrorCode.HYPERLINK_TASK_STATE_CONFLICT);
    }
}
