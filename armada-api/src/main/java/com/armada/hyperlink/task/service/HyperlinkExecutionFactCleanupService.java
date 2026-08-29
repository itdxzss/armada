package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountStatMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountUsageMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundAccountMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 未消费轮次、账号容量和查询投影清理；已有 command 的事实不会命中删除条件。 */
@Service
public class HyperlinkExecutionFactCleanupService {
    private final HyperlinkTaskRoundAccountMapper roundAccountMapper;
    private final HyperlinkTaskAccountUsageMapper usageMapper;
    private final HyperlinkTaskRoundMapper roundMapper;
    private final HyperlinkTaskAccountStatMapper statMapper;

    public HyperlinkExecutionFactCleanupService(HyperlinkTaskRoundAccountMapper roundAccountMapper,
            HyperlinkTaskAccountUsageMapper usageMapper, HyperlinkTaskRoundMapper roundMapper,
            HyperlinkTaskAccountStatMapper statMapper) {
        this.roundAccountMapper = roundAccountMapper;
        this.usageMapper = usageMapper;
        this.roundMapper = roundMapper;
        this.statMapper = statMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public void cleanup(long taskId) {
        roundAccountMapper.deleteUnconsumedByTask(taskId);
        usageMapper.deleteUnusedByTask(taskId);
        roundMapper.deleteUnconsumed(taskId);
        statMapper.deleteByTask(taskId);
    }
}
