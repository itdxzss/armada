package com.armada.group.scheduler;

import com.armada.group.mapper.GroupModelBackfillMapper;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 显式启用后分批回填新模型；当前只实现 wa_group，且只允许在一个实例启用。 */
@Component
@ConditionalOnProperty(
        prefix = "armada.group-model-backfill",
        name = "enabled",
        havingValue = "true")
public class GroupModelBackfillJob {

    private static final Logger log = LoggerFactory.getLogger(GroupModelBackfillJob.class);
    private static final int BATCH_SIZE = 500;

    private final GroupModelBackfillMapper mapper;

    /**
     * 创建群模型回填任务。
     *
     * @param mapper 回填数据访问
     */
    public GroupModelBackfillJob(GroupModelBackfillMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 执行一轮有界回填；任何重复群 JID 都阻断写入，禁止静默折叠旧入口。
     *
     * @return 本轮 wa_group 写入数量
     * @throws BusinessException 当旧入口不能唯一映射到群 JID 时抛出
     */
    @Scheduled(fixedDelayString = "${armada.group-model-backfill.fixed-delay-ms:30000}")
    @Transactional(rollbackFor = Exception.class)
    public BackfillResult backfillOnce() {
        int invalidSources = mapper.countInvalidGroupSources();
        if (invalidSources > 0) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "群模型回填发现非法群来源，已停止写入: " + invalidSources);
        }
        int conflicts = mapper.countDuplicateGroupJids();
        if (conflicts > 0) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "群模型回填发现租户内重复群 JID，已停止写入: " + conflicts);
        }
        int groupRows = mapper.backfillGroups(BATCH_SIZE);
        if (groupRows > 0) {
            log.info("群模型存量回填完成一批 groupRows={}", groupRows);
        }
        return new BackfillResult(groupRows);
    }

    /** 一轮回填结果。 */
    public record BackfillResult(int groupRows) {
    }
}
