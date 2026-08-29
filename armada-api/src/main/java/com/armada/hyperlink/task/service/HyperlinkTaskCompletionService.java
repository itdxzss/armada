package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 任务自然完成时先收口既有计费 Saga，再原子写 COMPLETED。 */
@Service
public class HyperlinkTaskCompletionService {
    private final HyperlinkTaskRecipientMapper recipientMapper;
    private final HyperlinkTaskAccountUsageMapper usageMapper;
    private final HyperlinkTaskRuntimeMapper runtimeMapper;
    private final HyperlinkBillingSagaService billingSagaService;
    private final Clock clock;

    @Autowired
    public HyperlinkTaskCompletionService(HyperlinkTaskRecipientMapper recipientMapper,
            HyperlinkTaskAccountUsageMapper usageMapper, HyperlinkTaskRuntimeMapper runtimeMapper,
            HyperlinkBillingSagaService billingSagaService) {
        this(recipientMapper, usageMapper, runtimeMapper, billingSagaService, Clock.systemUTC());
    }

    HyperlinkTaskCompletionService(HyperlinkTaskRecipientMapper recipientMapper,
            HyperlinkTaskAccountUsageMapper usageMapper, HyperlinkTaskRuntimeMapper runtimeMapper,
            HyperlinkBillingSagaService billingSagaService, Clock clock) {
        this.recipientMapper = recipientMapper;
        this.usageMapper = usageMapper;
        this.runtimeMapper = runtimeMapper;
        this.billingSagaService = billingSagaService;
        this.clock = clock;
    }

    /** 幂等完成；任何 SENDING/inFlight 都会阻止计费和状态终结。 */
    public void completeIfReady(long taskId) {
        if (recipientMapper.countUnsettledByTaskId(taskId) > 0
                || usageMapper.countInFlight(taskId) > 0) {
            return;
        }
        billingSagaService.finalizeBilling(taskId);
        runtimeMapper.markCompletedIfIdle(taskId, clock.millis());
    }
}
