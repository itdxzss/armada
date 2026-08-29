package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 旧事实释放完成后，按任务当前冻结配置重新报价并建立新 claim/billing 事实。 */
@Service
public class HyperlinkRebuildProvisionService {
    private final HyperlinkTaskMapper taskMapper;
    private final HyperlinkTaskQuoteGuardService quoteGuard;
    private final HyperlinkProvisionFactService provisionFactService;
    private final HyperlinkTaskConfigurationFactory configurationFactory;

    public HyperlinkRebuildProvisionService(HyperlinkTaskMapper taskMapper,
            HyperlinkTaskQuoteGuardService quoteGuard,
            HyperlinkProvisionFactService provisionFactService,
            HyperlinkTaskConfigurationFactory configurationFactory) {
        this.taskMapper = taskMapper;
        this.quoteGuard = quoteGuard;
        this.provisionFactService = provisionFactService;
        this.configurationFactory = configurationFactory;
    }

    @Transactional(rollbackFor = Exception.class)
    public void rebuild(long taskId) {
        long now = System.currentTimeMillis();
        HyperlinkTask task = taskMapper.selectById(taskId);
        HyperlinkQuoteTokenService.QuoteClaims claims = quoteGuard.internalForTask(task);
        configurationFactory.applyPackageSnapshot(task, claims);
        taskMapper.updatePackageSnapshot(taskId, claims.dataPackageGeneration(),
                claims.quote().dataPackageName(), task.getTargetCountryIso2sSnapshot(), now);
        provisionFactService.prepare(task, claims, now);
    }
}
