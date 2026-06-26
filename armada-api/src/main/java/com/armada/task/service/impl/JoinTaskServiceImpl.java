package com.armada.task.service.impl;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.mapper.JoinTaskResultMapper;
import com.armada.task.model.dto.CreateJoinTaskRequest;
import com.armada.task.model.dto.PlanRow;
import com.armada.task.model.dto.SelectedAccount;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskResult;
import com.armada.task.model.enums.DistributionMode;
import com.armada.task.model.enums.JoinResultStatus;
import com.armada.task.model.enums.JoinTaskStatus;
import com.armada.task.model.vo.JoinTaskVO;
import com.armada.task.service.JoinTaskService;
import com.armada.task.service.JsonIds;
import com.armada.task.service.LinkClassifier;
import com.armada.task.service.PlanRowGenerator;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 进群任务业务实现(第一刀:建任务)。
 *
 * <p>租户隔离由 MyBatis 租户拦截器透明完成,本类不手写 tenant_id。</p>
 * <p>时间字段为 BIGINT epoch 毫秒,insert 时显式传入。total 只计 PENDING 计划行(无效链接转 FAILED 行不计入)。</p>
 */
@Service
public class JoinTaskServiceImpl implements JoinTaskService {

    private static final Logger log = LoggerFactory.getLogger(JoinTaskServiceImpl.class);

    private final JoinTaskMapper joinTaskMapper;
    private final JoinTaskResultMapper resultMapper;

    public JoinTaskServiceImpl(JoinTaskMapper joinTaskMapper, JoinTaskResultMapper resultMapper) {
        this.joinTaskMapper = joinTaskMapper;
        this.resultMapper = resultMapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:计划行生成委托纯函数 {@link PlanRowGenerator},本方法只做参数归一、快照列序列化
     * (分组/账号 id → JSON)、计数与落库;total 只数 PENDING 行,无效链接的 FAILED 行入明细但不计入。
     * 整体在单事务内:先 insert join_task 拿回自增 id,再批量 insert join_task_result。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public JoinTaskVO createTask(CreateJoinTaskRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务名称不能为空");
        }
        log.info("建进群任务开始 name={} mode={} 选中账号数={} 链接文本长度={}",
                req.name(), req.distributionMode(),
                req.selectedAccounts() == null ? 0 : req.selectedAccounts().size(),
                req.linksText() == null ? 0 : req.linksText().length());
        String mode = DistributionMode.FIXED_ACCOUNT_MULTI_LINK.equals(req.distributionMode())
                ? DistributionMode.FIXED_ACCOUNT_MULTI_LINK : DistributionMode.FIXED_ACCOUNTS_PER_LINK;
        LinkClassifier.Classified links = LinkClassifier.classify(req.linksText());
        List<SelectedAccount> accounts = req.selectedAccounts() == null ? List.of() : req.selectedAccounts();
        List<PlanRow> rows = PlanRowGenerator.generate(mode, accounts, links.valid(), links.invalid(),
                n(req.accountsPerLink()), n(req.executorAccountCount()), n(req.linksPerAccount()));
        int total = (int) rows.stream().filter(r -> JoinResultStatus.PENDING.equals(r.status())).count();
        long now = System.currentTimeMillis();

        JoinTask task = new JoinTask();
        task.setName(req.name().trim());
        task.setAccountGroupIds(JsonIds.toJson(req.accountGroupIds()));
        task.setAccountGroupNames(joinNames(req.accountGroupNames()));
        task.setSelectedAccountIds(JsonIds.toJson(JsonIds.idsOf(accounts)));
        task.setLinksText(req.linksText() == null ? "" : req.linksText());
        task.setDistributionMode(mode);
        task.setAccountsPerLink(n(req.accountsPerLink()));
        task.setExecutorAccountCount(n(req.executorAccountCount()));
        task.setLinksPerAccount(n(req.linksPerAccount()));
        task.setFixedIntervalMinSec(n(req.fixedIntervalMinSec()));
        task.setFixedIntervalMaxSec(n(req.fixedIntervalMaxSec()));
        task.setMultiIntervalMinSec(n(req.multiIntervalMinSec()));
        task.setMultiIntervalMaxSec(n(req.multiIntervalMaxSec()));
        task.setIntervalLabel(intervalLabel(mode, req));
        task.setRetryEnabled(Boolean.TRUE.equals(req.retryEnabled()));
        task.setRetryLimit(n(req.retryLimit()));
        task.setFailurePolicy(req.failurePolicy() == null ? "" : req.failurePolicy());
        task.setTotal(total);
        task.setExecuted(0);
        task.setSuccess(0);
        task.setFailed(0);
        task.setPending(total);
        task.setStatus(JoinTaskStatus.DRAFT);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        joinTaskMapper.insert(task);

        persistRows(task.getId(), rows, now);
        log.info("建进群任务完成 id={} mode={} total={} 计划行={}", task.getId(), mode, total, rows.size());
        return toVO(joinTaskMapper.selectByTenantAndId(task.getId()));
    }

    /** 计划行落库:每行补 joinTaskId/createdAt/updatedAt 后批量插入;空则跳过。 */
    private void persistRows(Long taskId, List<PlanRow> rows, long now) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<JoinTaskResult> entities = new ArrayList<>(rows.size());
        for (PlanRow r : rows) {
            JoinTaskResult e = new JoinTaskResult();
            e.setJoinTaskId(taskId);
            e.setAccount(r.account());
            e.setAccountId(r.accountId());
            e.setLink(r.link());
            e.setStatus(r.status());
            e.setReason(r.reason());
            e.setCreatedAt(now);
            e.setUpdatedAt(now);
            entities.add(e);
        }
        resultMapper.insertResults(entities);
    }

    /** Integer 归一:null/负 → 0。 */
    private static int n(Integer x) {
        return x == null || x < 0 ? 0 : x;
    }

    /** 分组名快照:以 "/" 连接;null/空 → ""。 */
    private static String joinNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        return String.join("/", names);
    }

    /** 进群间隔展示标签:方式二取 multi 区间,否则取 fixed 区间,形如 "10-20s"。 */
    private static String intervalLabel(String mode, CreateJoinTaskRequest req) {
        int lo;
        int hi;
        if (DistributionMode.FIXED_ACCOUNT_MULTI_LINK.equals(mode)) {
            lo = n(req.multiIntervalMinSec());
            hi = n(req.multiIntervalMaxSec());
        } else {
            lo = n(req.fixedIntervalMinSec());
            hi = n(req.fixedIntervalMaxSec());
        }
        return lo + "-" + hi + "s";
    }

    /** 实体 → 列表行 VO。 */
    private static JoinTaskVO toVO(JoinTask t) {
        return new JoinTaskVO(t.getId(), t.getName(), t.getAccountGroupNames(),
                t.getTotal(), t.getExecuted(), t.getSuccess(), t.getFailed(), t.getPending(),
                t.getIntervalLabel(), t.getDistributionMode(), t.getFailurePolicy(),
                t.isRetryEnabled(), t.getRetryLimit(), t.getStatus(), t.getCreatedBy(), t.getCreatedAt());
    }
}
