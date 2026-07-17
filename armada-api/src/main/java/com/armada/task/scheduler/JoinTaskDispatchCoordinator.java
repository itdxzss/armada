package com.armada.task.scheduler;

import com.armada.platform.protocol.model.enums.ProtocolCommandOutboxStatus;
import com.armada.task.mapper.JoinTaskResultMapper;
import com.armada.task.model.dto.JoinTaskDeadCommandCandidate;
import com.armada.task.model.dto.JoinTaskDispatchCandidate;
import com.armada.task.service.JoinTaskResultService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 组织一轮进群命令到期派发和 outbox 传输失败收敛。
 *
 * <p>候选扫描需要覆盖所有租户，因此只返回 tenantId/resultId 且不持锁；随后按租户分组，逐组进入
 * {@link JoinTaskDispatchTransactionService} 的短事务。这个两段式结构既保留租户隔离，又避免在扫描
 * 期间持有大量行锁。协议命令的 Kafka 发布由通用 outbox dispatcher 负责，本协调器不等待网络。</p>
 */
@Component
public class JoinTaskDispatchCoordinator {

    /** 调度轮次摘要日志，不记录账号、手机号或群邀请码。 */
    private static final Logger log = LoggerFactory.getLogger(JoinTaskDispatchCoordinator.class);

    /** 进群明细查询入口，负责跨租户候选扫描。 */
    private final JoinTaskResultMapper resultMapper;

    /** 单租户派发事务，负责加锁、写 outbox 和状态迁移。 */
    private final JoinTaskDispatchTransactionService transactionService;

    /** 任务结果状态机，用于把 outbox DEAD 收敛为业务重试或终态。 */
    private final JoinTaskResultService resultService;

    /** 独立进群调度器的轮询与批量配置。 */
    private final JoinTaskDispatchProperties properties;

    /** 可替换时钟，生产使用系统时间，测试使用确定时间。 */
    private final LongSupplier currentTimeMillis;

    /**
     * 创建使用系统时钟的调度协调器。
     *
     * @param resultMapper 进群明细 Mapper
     * @param transactionService 单租户派发事务服务
     * @param resultService 进群结果状态机
     * @param properties 调度器配置
     */
    @Autowired
    public JoinTaskDispatchCoordinator(JoinTaskResultMapper resultMapper,
                                       JoinTaskDispatchTransactionService transactionService,
                                       JoinTaskResultService resultService,
                                       JoinTaskDispatchProperties properties) {
        this(resultMapper, transactionService, resultService, properties, System::currentTimeMillis);
    }

    /**
     * 创建可注入时钟的调度协调器，供确定性单元测试使用。
     *
     * @param resultMapper 进群明细 Mapper
     * @param transactionService 单租户派发事务服务
     * @param resultService 进群结果状态机
     * @param properties 调度器配置
     * @param currentTimeMillis 当前 epoch 毫秒提供器
     */
    public JoinTaskDispatchCoordinator(JoinTaskResultMapper resultMapper,
                                       JoinTaskDispatchTransactionService transactionService,
                                       JoinTaskResultService resultService,
                                       JoinTaskDispatchProperties properties,
                                       LongSupplier currentTimeMillis) {
        this.resultMapper = resultMapper;
        this.transactionService = transactionService;
        this.resultService = resultService;
        this.properties = properties;
        this.currentTimeMillis = currentTimeMillis;
    }

    /**
     * 执行一轮有界调度。
     *
     * <p>先处理最多 batchSize 条 outbox DEAD，再扫描最多 batchSize 条到期明细。任一异常向上抛出，
     * 由外层 scheduler 记录整轮失败；已经提交的独立事务不会被后续租户异常回滚。</p>
     *
     * @return 本轮扫描、锁定、入队和跳过数量
     */
    public JoinTaskDispatchStats dispatchOnce() {
        int batchSize = properties.getBatchSize();
        for (JoinTaskDeadCommandCandidate dead : resultMapper.selectDeadSubmittedCandidates(
                ProtocolCommandOutboxStatus.DEAD.code(), batchSize)) {
            resultService.applyTransportFailure(dead);
        }

        long now = currentTimeMillis.getAsLong();
        List<JoinTaskDispatchCandidate> candidates = resultMapper.selectDueCandidates(now, batchSize);
        Map<Long, List<Long>> byTenant = new LinkedHashMap<>();
        for (JoinTaskDispatchCandidate candidate : candidates) {
            byTenant.computeIfAbsent(candidate.tenantId(), ignored -> new java.util.ArrayList<>())
                    .add(candidate.resultId());
        }
        JoinTaskDispatchStats total = JoinTaskDispatchStats.empty();
        for (Map.Entry<Long, List<Long>> entry : byTenant.entrySet()) {
            total = total.plus(transactionService.dispatchTenant(entry.getKey(), entry.getValue(), now));
        }
        log.info("进群到期调度完成 scanned={} claimed={} enqueued={} skipped={}",
                candidates.size(), total.claimed(), total.enqueued(), total.skipped());
        return new JoinTaskDispatchStats(candidates.size(), total.claimed(), total.enqueued(), total.skipped());
    }
}
