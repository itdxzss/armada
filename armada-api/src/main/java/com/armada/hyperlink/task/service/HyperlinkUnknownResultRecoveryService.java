package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.model.vo.HyperlinkReconciliationCandidate;
import com.armada.platform.protocol.port.MessageCommandRecoveryPort;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 到期 SENDING 仅重排原 outbox command，不创建新命令或更换账号。 */
@Service
public class HyperlinkUnknownResultRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(
            HyperlinkUnknownResultRecoveryService.class);
    private static final long RETRY_DELAY_MS = 30_000L;
    private static final long ANDROID_SAFE_REPLAY_WINDOW_MS = 29L * 24 * 60 * 60 * 1_000;
    private static final long RETENTION_GUARD_RECHECK_MS = 24L * 60 * 60 * 1_000;
    private static final int ANDROID_BACKEND = 2;

    private final HyperlinkTaskRecipientMapper recipientMapper;
    private final MessageCommandRecoveryPort recoveryPort;
    private final HyperlinkAccountDispatchGuard dispatchGuard;
    private final Clock clock;

    public HyperlinkUnknownResultRecoveryService(HyperlinkTaskRecipientMapper recipientMapper,
            MessageCommandRecoveryPort recoveryPort,
            HyperlinkAccountDispatchGuard dispatchGuard) {
        this(recipientMapper, recoveryPort, dispatchGuard, Clock.systemUTC());
    }

    HyperlinkUnknownResultRecoveryService(HyperlinkTaskRecipientMapper recipientMapper,
            MessageCommandRecoveryPort recoveryPort,
            HyperlinkAccountDispatchGuard dispatchGuard, Clock clock) {
        this.recipientMapper = recipientMapper;
        this.recoveryPort = recoveryPort;
        this.dispatchGuard = dispatchGuard;
        this.clock = clock;
    }

    /**
     * 恢复一个原命令。Android tombstone 当前保证 30 天，故只在 29 天安全窗口内重排；
     * 超窗保持 SENDING，等待明确结果或外部能力门禁处理，绝不猜成失败。
     */
    @Transactional(rollbackFor = Exception.class)
    public void recover(HyperlinkReconciliationCandidate candidate) {
        long now = clock.millis();
        dispatchGuard.renew(candidate.accountId(), candidate.commandId());
        if (candidate.protocolBackend() == ANDROID_BACKEND
                && (candidate.submittedAt() == null
                || candidate.submittedAt() < now - ANDROID_SAFE_REPLAY_WINDOW_MS)) {
            recipientMapper.scheduleReconciliation(candidate.commandId(),
                    now + RETENTION_GUARD_RECHECK_MS, now);
            log.error("hyperlink UNKNOWN exceeds Android idempotency retention taskId={} recipientId={}",
                    candidate.taskId(), candidate.recipientId());
            return;
        }
        recoveryPort.replay(candidate.tenantId(), candidate.commandId(), now);
        recipientMapper.scheduleReconciliation(candidate.commandId(), now + RETRY_DELAY_MS, now);
    }
}
