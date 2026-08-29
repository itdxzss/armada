package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 按公共隐私合同清理 90 天前首触环境，永久指标不受影响。 */
@Service
public class HyperlinkAttributionRetentionService {
    static final int BATCH_SIZE = 2_000;
    private static final long RETENTION_MS = Duration.ofDays(90).toMillis();
    private final HyperlinkTaskRecipientMapper recipientMapper;

    public HyperlinkAttributionRetentionService(HyperlinkTaskRecipientMapper recipientMapper) {
        this.recipientMapper = recipientMapper;
    }

    @Transactional
    public int purgeOneBatch(long now) {
        long cutoff = now - RETENTION_MS;
        int changed = 0;
        for (var row : recipientMapper.selectAttributionRetentionCandidates(cutoff, BATCH_SIZE)) {
            changed += recipientMapper.purgeAttribution(row.getTenantId(), row.getId(), cutoff, now);
        }
        return changed;
    }
}
