package com.armada.task.mapper;

import com.armada.boot.Application;
import com.armada.task.model.dto.JoinTaskDeadCommandCandidate;
import com.armada.task.model.dto.JoinTaskDispatchCandidate;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskResult;
import com.armada.task.model.enums.JoinTaskDispatchState;
import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 进群异步调度 Mapper 的真实 MySQL 状态机测试。
 *
 * <p>本类不使用 H2 或 mock Mapper，直接验证 MySQL 的派生表 UPDATE、
 * {@code FOR UPDATE SKIP LOCKED}、同账号 lane 的 {@code NOT EXISTS} 闸门，以及
 * 跨表匹配 outbox DEAD 命令。测试事务由 {@link DbTestBase} 回滚。</p>
 *
 * <p>禁用进群周期调度线程，避免它与测试事务竞争候选行；被测 SQL 仍由测试
 * 直接调用。</p>
 */
@SpringBootTest(
        classes = Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "armada.task.join-dispatcher.enabled=false")
class JoinTaskDispatchMapperDbTest extends DbTestBase {

    @Autowired
    JoinTaskMapper taskMapper;

    @Autowired
    JoinTaskResultMapper resultMapper;

    @Autowired
    JdbcTemplate jdbc;

    /**
     * 启动时应同时激活所有账号 lane，但每个账号任一时刻只能有一条 SUBMITTED。
     *
     * <p>用账号 101 的第二条明细人为到期，验证第一条已提交后数据库闸门会拒绝
     * 它；账号 102 的首条仍能与 101 并行被锁定。</p>
     */
    @Test
    void startAndDueClaim_activateEveryAccountButSerializeSameAccount() {
        long now = System.currentTimeMillis();
        JoinTask task = insertTask("DRAFT", 3, now);
        resultMapper.insertResults(List.of(
                pending(task.getId(), 101L, "a-1", now),
                pending(task.getId(), 101L, "a-2", now + 1),
                pending(task.getId(), 102L, "b-1", now + 2)
        ));

        assertThat(taskMapper.startDraftTask(task.getId(), now + 10)).isEqualTo(1);
        assertThat(taskMapper.startDraftTask(task.getId(), now + 11)).isZero();
        assertThat(resultMapper.activateFirstPendingPerAccount(task.getId(), now + 10)).isEqualTo(2);

        List<JoinTaskResult> rows = rows(task.getId());
        JoinTaskResult account101First = rows.get(0);
        JoinTaskResult account101Second = rows.get(1);
        JoinTaskResult account102First = rows.get(2);
        assertThat(rows).extracting(JoinTaskResult::getNextExecuteAt)
                .containsExactly(now + 10, null, now + 10);

        List<JoinTaskDispatchCandidate> candidates = resultMapper.selectDueCandidates(now + 10, 10);
        assertThat(candidates).extracting(JoinTaskDispatchCandidate::resultId)
                .containsExactly(account101First.getId(), account102First.getId());
        assertThat(candidates).extracting(JoinTaskDispatchCandidate::tenantId)
                .containsOnly(TEST_TENANT_ID);

        // 锁行 Mapper 关闭了自动租户改写，所以必须证明显式 tenantId 不会越权命中。
        assertThat(resultMapper.selectDueForUpdate(
                TEST_TENANT_ID + 1, List.of(account101First.getId(), account102First.getId()), now + 10))
                .isEmpty();
        List<JoinTaskResult> claimed = resultMapper.selectDueForUpdate(
                TEST_TENANT_ID, List.of(account101First.getId(), account102First.getId()), now + 10);
        assertThat(claimed).extracting(JoinTaskResult::getId)
                .containsExactly(account101First.getId(), account102First.getId());

        assertThat(resultMapper.markSubmitted(
                account101First.getId(), "join-command-1", 1, now + 10)).isEqualTo(1);
        assertThat(resultMapper.selectSubmittedForUpdate(
                account101First.getId(), "stale-command", 1)).isNull();
        assertThat(resultMapper.selectSubmittedForUpdate(
                account101First.getId(), "join-command-1", 1)).isNotNull();

        // 这个时间通常由前一条终态后的 activateNextPending 设置；直接写入用于隔离验证 lane 闸门。
        jdbc.update("UPDATE join_task_result SET next_execute_at=? WHERE id=? AND tenant_id=?",
                now + 10, account101Second.getId(), TEST_TENANT_ID);
        assertThat(resultMapper.selectDueForUpdate(
                TEST_TENANT_ID, List.of(account101Second.getId()), now + 10)).isEmpty();
    }

    /**
     * 验证一条明细从提交、业务重试到成功终态，并在真实间隔后激活同账号下一条。
     *
     * <p>重试不增加新明细：保留 attempt_no、清空旧 command_id，且在 next_execute_at 之前不允许
     * 再次提交。全部明细终结后任务才能进入 DONE。</p>
     */
    @Test
    void retryAndTerminalTransitions_preserveIntervalAndCompleteTask() {
        long now = System.currentTimeMillis();
        JoinTask task = insertTask("RUNNING", 2, now);
        resultMapper.insertResults(List.of(
                pending(task.getId(), 201L, "retry-1", now),
                pending(task.getId(), 201L, "retry-2", now + 1)
        ));
        assertThat(resultMapper.activateFirstPendingPerAccount(task.getId(), now)).isEqualTo(1);

        List<JoinTaskResult> initial = rows(task.getId());
        JoinTaskResult first = initial.get(0);
        JoinTaskResult second = initial.get(1);
        assertThat(resultMapper.markSubmitted(first.getId(), "attempt-1", 1, now)).isEqualTo(1);

        long retryAt = now + 5_000;
        assertThat(resultMapper.markRetry(first.getId(), "NETWORK_ERROR", retryAt, now + 100)).isEqualTo(1);
        JoinTaskResult waitingRetry = rows(task.getId()).get(0);
        assertThat(waitingRetry.getDispatchState()).isEqualTo(JoinTaskDispatchState.WAITING);
        assertThat(waitingRetry.getCommandId()).isNull();
        assertThat(waitingRetry.getAttemptNo()).isEqualTo(1);
        assertThat(waitingRetry.getNextExecuteAt()).isEqualTo(retryAt);
        assertThat(waitingRetry.getReason()).isEqualTo("NETWORK_ERROR");

        assertThat(resultMapper.markSubmitted(first.getId(), "too-early", 2, retryAt - 1)).isZero();
        assertThat(resultMapper.markSubmitted(first.getId(), "attempt-2", 2, retryAt)).isEqualTo(1);
        assertThat(resultMapper.markTerminalSuccess(
                first.getId(), "120363000001@g.us", retryAt + 100)).isEqualTo(1);
        assertThat(resultMapper.markTerminalFailure(
                first.getId(), "LATE_FAILURE", retryAt + 101)).isZero();

        long nextLaneAt = retryAt + 3_000;
        assertThat(resultMapper.activateNextPending(
                task.getId(), 201L, first.getId(), nextLaneAt, retryAt + 100)).isEqualTo(1);
        assertThat(resultMapper.activateNextPending(
                task.getId(), 201L, first.getId(), nextLaneAt, retryAt + 101)).isZero();

        List<JoinTaskResult> afterSuccess = rows(task.getId());
        assertThat(afterSuccess.get(0).getStatus()).isEqualTo("SUCCESS");
        assertThat(afterSuccess.get(0).getDispatchState()).isEqualTo(JoinTaskDispatchState.TERMINAL);
        assertThat(afterSuccess.get(0).getGroupJid()).isEqualTo("120363000001@g.us");
        assertThat(afterSuccess.get(0).getJoinedAt()).isEqualTo(retryAt + 100);
        assertThat(afterSuccess.get(1).getNextExecuteAt()).isEqualTo(nextLaneAt);

        assertThat(taskMapper.markDoneWhenNoPending(task.getId(), nextLaneAt)).isZero();
        assertThat(resultMapper.markTerminalFailure(
                second.getId(), "INVALID_INVITE", nextLaneAt + 1)).isEqualTo(1);
        assertThat(taskMapper.markDoneWhenNoPending(task.getId(), nextLaneAt + 2)).isEqualTo(1);
        assertThat(taskMapper.selectByTenantAndId(task.getId()).getStatus()).isEqualTo("DONE");
    }

    /**
     * outbox DEAD 只能命中明细当前的 command_id，不能把同明细的历史失败命令当成当前尝试。
     */
    @Test
    void deadScan_matchesCurrentSubmittedCommandAndIgnoresHistory() {
        long now = System.currentTimeMillis();
        JoinTask task = insertTask("RUNNING", 1, now);
        resultMapper.insertResults(List.of(pending(task.getId(), 301L, "dead-1", now)));
        assertThat(resultMapper.activateFirstPendingPerAccount(task.getId(), now)).isEqualTo(1);
        JoinTaskResult row = rows(task.getId()).get(0);
        assertThat(resultMapper.markSubmitted(row.getId(), "current-dead", 3, now)).isEqualTo(1);

        insertOutbox(row.getId(), "historical-dead", 3, now);
        insertOutbox(row.getId(), "current-dead", 3, now + 1);

        List<JoinTaskDeadCommandCandidate> dead = resultMapper.selectDeadSubmittedCandidates(3, 10);
        assertThat(dead).containsExactly(new JoinTaskDeadCommandCandidate(
                TEST_TENANT_ID, row.getId(), "current-dead", 3));
        assertThat(resultMapper.selectDeadSubmittedCandidates(2, 10)).isEmpty();
    }

    /** 插入一条字段完整但内容最小的任务，并返回 MySQL 生成的主键。 */
    private JoinTask insertTask(String status, int total, long now) {
        JoinTask task = new JoinTask();
        task.setName("进群调度DbTest");
        task.setAccountGroupIds("[]");
        task.setAccountGroupNames("");
        task.setSelectedAccountIds("[]");
        task.setLinksText("");
        task.setDistributionMode("FIXED_ACCOUNT_MULTI_LINK");
        task.setAccountsPerLink(0);
        task.setExecutorAccountCount(total);
        task.setLinksPerAccount(1);
        task.setFixedIntervalMinSec(0);
        task.setFixedIntervalMaxSec(0);
        task.setMultiIntervalMinSec(3);
        task.setMultiIntervalMaxSec(5);
        task.setIntervalLabel("3-5s");
        task.setRetryEnabled(true);
        task.setRetryLimit(3);
        task.setFailurePolicy("SKIP");
        task.setTotal(total);
        task.setExecuted(0);
        task.setSuccess(0);
        task.setFailed(0);
        task.setPending(total);
        task.setStatus(status);
        task.setCreatedBy(1L);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        assertThat(taskMapper.insert(task)).isEqualTo(1);
        return task;
    }

    /** 构造未激活的 WAITING 明细；next_execute_at 必须由账号 lane 激活 SQL 写入。 */
    private static JoinTaskResult pending(Long taskId, Long accountId, String suffix, long now) {
        JoinTaskResult row = new JoinTaskResult();
        row.setJoinTaskId(taskId);
        row.setAccount("account-" + accountId);
        row.setAccountId(accountId);
        row.setLink("https://chat.whatsapp.com/" + suffix);
        row.setStatus("PENDING");
        row.setDispatchState(JoinTaskDispatchState.WAITING);
        row.setAttemptNo(0);
        row.setReason("");
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    /** 重新从 Mapper 读取指定任务的有序明细，避免依赖 insertResults 的主键回填。 */
    private List<JoinTaskResult> rows(Long taskId) {
        return resultMapper.selectResultsByTask(taskId);
    }

    /**
     * 直接写入最小 outbox 行，把用例聚焦在 DEAD 查询的跨表匹配条件。
     *
     * <p>status=3 代表 DEAD；aggregate_type/aggregate_id 与生产进群命令保持一致。</p>
     */
    private void insertOutbox(Long resultId, String commandId, int status, long now) {
        jdbc.update("""
                INSERT INTO protocol_command_outbox
                  (tenant_id, command_id, command_type, aggregate_type, aggregate_id,
                   kafka_topic, kafka_key, protocol_account_id, payload_json,
                   status, retry_count, next_retry_at, created_at, updated_at)
                VALUES (?, ?, 'group.join.requested', 'JOIN_TASK_RESULT', ?,
                        'protocol.account.commands.v1', ?, ?, '{}', ?, 0, 0, ?, ?)
                """,
                TEST_TENANT_ID, commandId, resultId,
                "join-result-" + resultId, "account-301", status, now, now);
    }
}
