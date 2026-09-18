package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.model.vo.HyperlinkReconciliationCandidate;
import com.armada.platform.protocol.port.MessageCommandRecoveryPort;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 到期 SENDING 查询原 command；Android 使用无发送副作用的独立 query 命令。 */
@Service
public class HyperlinkUnknownResultRecoveryService {
    private static final long RETRY_DELAY_MS = 30_000L;

    private final HyperlinkTaskRecipientMapper recipientMapper;
    private final MessageCommandRecoveryPort recoveryPort;
    private final HyperlinkAccountDispatchGuard dispatchGuard;
    private final Clock clock;

    @Autowired
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
     * 持续读取原命令事实。Android 即使缓存不存在也只能返回未知，禁止退回发送入口。
     */
    @Transactional(rollbackFor = Exception.class)
    public void recover(HyperlinkReconciliationCandidate candidate) {
        long now = clock.millis();
        dispatchGuard.renew(candidate.accountId(), candidate.commandId());
        recoveryPort.replay(candidate.tenantId(), candidate.commandId(), now);
        recipientMapper.scheduleReconciliation(candidate.commandId(), now + RETRY_DELAY_MS, now);
    }
}
