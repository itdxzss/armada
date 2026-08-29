package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import org.springframework.stereotype.Service;

/** 可恢复清理编排：每次仅释放一批 recipient，收敛后再清容量、计费并按目标重建。 */
@Service
public class HyperlinkCleanupService {
    private final HyperlinkRecipientCleanupService recipientCleanupService;
    private final HyperlinkExecutionFactCleanupService executionFactCleanupService;
    private final HyperlinkBillingSagaService billingSagaService;
    private final HyperlinkTaskRuntimeMapper runtimeMapper;
    private final HyperlinkRebuildProvisionService rebuildProvisionService;

    public HyperlinkCleanupService(HyperlinkRecipientCleanupService recipientCleanupService,
            HyperlinkExecutionFactCleanupService executionFactCleanupService,
            HyperlinkBillingSagaService billingSagaService, HyperlinkTaskRuntimeMapper runtimeMapper,
            HyperlinkRebuildProvisionService rebuildProvisionService) {
        this.recipientCleanupService = recipientCleanupService;
        this.executionFactCleanupService = executionFactCleanupService;
        this.billingSagaService = billingSagaService;
        this.runtimeMapper = runtimeMapper;
        this.rebuildProvisionService = rebuildProvisionService;
    }

    public void advance(long taskId) {
        billingSagaService.ensureCleanupSafe(taskId);
        if (!recipientCleanupService.cleanupBatch(taskId)) { return; }
        HyperlinkTaskRuntime runtime = runtimeMapper.selectByTaskId(taskId);
        if (runtime != null && runtime.getRunStatus() != 4
                && Boolean.TRUE.equals(runtime.getEnabled())) {
            executionFactCleanupService.cleanup(taskId);
            rebuildProvisionService.rebuild(taskId);
            return;
        }
        billingSagaService.finalizeBilling(taskId);
        if (runtime == null || runtime.getRunStatus() == 4) { return; }
        executionFactCleanupService.cleanup(taskId);
        if (!Boolean.TRUE.equals(runtime.getEnabled())) {
            runtimeMapper.finishCleanupAsDraft(taskId, System.currentTimeMillis());
        }
    }
}
