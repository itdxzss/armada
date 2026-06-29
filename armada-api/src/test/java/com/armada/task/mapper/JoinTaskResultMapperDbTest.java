package com.armada.task.mapper;

import com.armada.task.model.entity.JoinTaskResult;
import com.armada.testsupport.DbTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JoinTaskResultMapper 真库测试：验 insertResults/selectResultsByTask/deleteResultsByTask 全路径，
 * 以及 is_admin 列显式 resultMap 映射的正向验证（用例 4）。
 *
 * <p>每个 @Test 在 @Transactional 内执行并回滚，数据互不干扰。</p>
 * <p>join_task_result 无外键约束，使用固定 joinTaskId 避免依赖父行插入。</p>
 */
class JoinTaskResultMapperDbTest extends DbTestBase {

    /** 测试用固定进群任务 ID（join_task_result 无 FK 约束，无需 join_task 父行）。 */
    private static final long TEST_TASK_ID = 9_000_000_001L;

    @Autowired
    JoinTaskResultMapper mapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private JoinTaskResult build(String account, Long accountId, String link, String status, String reason) {
        long now = System.currentTimeMillis();
        JoinTaskResult r = new JoinTaskResult();
        r.setJoinTaskId(TEST_TASK_ID);
        r.setAccount(account);
        r.setAccountId(accountId);
        r.setLink(link);
        r.setStatus(status);
        r.setReason(reason);
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        return r;
    }

    // ─── 用例 1：insertResults + selectResultsByTask 字段一致性（含 DB 默认值校验）───

    @Test
    void insertResults_then_selectResultsByTask_roundTrip() {
        JoinTaskResult r1 = build("8610001", 101L, "https://chat.whatsapp.com/abc", "PENDING", "");
        JoinTaskResult r2 = build("8610002", 102L, "https://chat.whatsapp.com/def", "SUCCESS", "");
        JoinTaskResult r3 = build("8610003", 103L, "https://chat.whatsapp.com/ghi", "FAILED", "无效链接");

        int inserted = mapper.insertResults(List.of(r1, r2, r3));
        assertThat(inserted).isEqualTo(3);

        List<JoinTaskResult> results = mapper.selectResultsByTask(TEST_TASK_ID);
        assertThat(results).hasSize(3);

        // ORDER BY id 保序，r1 最先插入所以 id 最小
        JoinTaskResult got1 = results.get(0);
        assertThat(got1.getJoinTaskId()).isEqualTo(TEST_TASK_ID);
        assertThat(got1.getAccount()).isEqualTo("8610001");
        assertThat(got1.getAccountId()).isEqualTo(101L);
        assertThat(got1.getLink()).isEqualTo("https://chat.whatsapp.com/abc");
        assertThat(got1.getStatus()).isEqualTo("PENDING");
        assertThat(got1.getReason()).isEqualTo("");
        // 引擎列 DB 默认值：group_jid='', is_admin=0(false), promoted_at=NULL
        assertThat(got1.getGroupJid()).isEqualTo("");
        assertThat(got1.isAdmin()).isFalse();
        assertThat(got1.getPromotedAt()).isNull();

        assertThat(results.get(1).getAccount()).isEqualTo("8610002");
        assertThat(results.get(1).getStatus()).isEqualTo("SUCCESS");
        assertThat(results.get(2).getAccount()).isEqualTo("8610003");
        assertThat(results.get(2).getReason()).isEqualTo("无效链接");
    }

    // ─── 用例 2：accountId 可空（无效链接占位行）────────────────────────────────

    @Test
    void insertResults_nullAccountId_readBackNull() {
        // 无效链接行：accountId 为 null，建任务时即写入失败原因
        JoinTaskResult r = build("8619999", null, "https://chat.whatsapp.com/invalid", "FAILED", "无效链接");

        mapper.insertResults(List.of(r));

        List<JoinTaskResult> results = mapper.selectResultsByTask(TEST_TASK_ID);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getAccountId()).isNull();
    }

    // ─── 用例 3：deleteResultsByTask 清空该任务全部计划行 ───────────────────────

    @Test
    void deleteResultsByTask_clearsAllRows() {
        JoinTaskResult r1 = build("8611001", 201L, "https://chat.whatsapp.com/link1", "PENDING", "");
        JoinTaskResult r2 = build("8611002", 202L, "https://chat.whatsapp.com/link2", "PENDING", "");

        mapper.insertResults(List.of(r1, r2));
        assertThat(mapper.selectResultsByTask(TEST_TASK_ID)).hasSize(2);

        int deleted = mapper.deleteResultsByTask(TEST_TASK_ID);
        assertThat(deleted).isEqualTo(2);
        assertThat(mapper.selectResultsByTask(TEST_TASK_ID)).isEmpty();
    }

    // ─── 用例 4：is_admin 显式 resultMap 正向验证（此用例是核心安全网）──────────

    @Test
    void isAdmin_explicitResultMap_positiveVerification() {
        // 先用 mapper 插入一行（is_admin 默认 0=false），不在此处 SELECT
        // 若先 SELECT 再 UPDATE，MyBatis 一级缓存会缓存旧结果（false）导致第二次 SELECT 走缓存
        JoinTaskResult r = build("8612001", 301L, "https://chat.whatsapp.com/admin", "PENDING", "");
        mapper.insertResults(List.of(r));

        // 用 JdbcTemplate 直接将 is_admin 改为 1，绕过 MyBatis，验证 resultMap 映射而非 insert 行为
        // 此时 SELECT 缓存未预热，下方 selectResultsByTask 将真正打 DB
        jdbcTemplate.update(
                "UPDATE join_task_result SET is_admin = 1 WHERE join_task_id = ? AND tenant_id = ?",
                TEST_TASK_ID, TEST_TENANT_ID
        );

        // 通过 selectResultsByTask 读回，断言 isAdmin()==true
        // 若读回 false，说明显式 resultMap 没绑住 is_admin → isAdmin（映射有误，必须修 XML）
        List<JoinTaskResult> after = mapper.selectResultsByTask(TEST_TASK_ID);
        assertThat(after).hasSize(1);
        assertThat(after.get(0).isAdmin())
                .as("is_admin=1 必须通过显式 resultMap 映射到 isAdmin()=true")
                .isTrue();
    }

    @Test
    void selectPendingResultsByTask_onlyReturnsPendingRows() {
        JoinTaskResult pending1 = build("8613001", 401L, "https://chat.whatsapp.com/pending1", "PENDING", "");
        JoinTaskResult success = build("8613002", 402L, "https://chat.whatsapp.com/success", "SUCCESS", "");
        JoinTaskResult failed = build("8613003", 403L, "https://chat.whatsapp.com/failed", "FAILED", "JOIN_FAILED");
        JoinTaskResult pending2 = build("8613004", 404L, "https://chat.whatsapp.com/pending2", "PENDING", "");
        mapper.insertResults(List.of(pending1, success, failed, pending2));

        List<JoinTaskResult> pending = mapper.selectPendingResultsByTask(TEST_TASK_ID);

        assertThat(pending).extracting(JoinTaskResult::getAccount)
                .containsExactly("8613001", "8613004");
    }

    @Test
    void updateResultSuccess_marksPendingRowSuccessAndBackfillsGroupJid() {
        JoinTaskResult pending = build("8614001", 501L, "https://chat.whatsapp.com/success", "PENDING", "");
        mapper.insertResults(List.of(pending));
        Long resultId = mapper.selectResultsByTask(TEST_TASK_ID).get(0).getId();

        int affected = mapper.updateResultSuccess(resultId, "120363999@g.us", System.currentTimeMillis() + 1);

        assertThat(affected).isEqualTo(1);
        JoinTaskResult updated = mapper.selectResultsByTask(TEST_TASK_ID).get(0);
        assertThat(updated.getStatus()).isEqualTo("SUCCESS");
        assertThat(updated.getGroupJid()).isEqualTo("120363999@g.us");
        assertThat(updated.getReason()).isEqualTo("");
    }

    @Test
    void updateResultFailed_marksPendingRowFailedAndKeepsSuccessRowsUntouched() {
        JoinTaskResult pending = build("8615001", 601L, "https://chat.whatsapp.com/failed", "PENDING", "");
        JoinTaskResult success = build("8615002", 602L, "https://chat.whatsapp.com/success", "SUCCESS", "");
        mapper.insertResults(List.of(pending, success));
        List<JoinTaskResult> before = mapper.selectResultsByTask(TEST_TASK_ID);
        Long pendingId = before.get(0).getId();
        Long successId = before.get(1).getId();

        assertThat(mapper.updateResultFailed(pendingId, "JOIN_FAILED", System.currentTimeMillis() + 1)).isEqualTo(1);
        assertThat(mapper.updateResultFailed(successId, "SHOULD_NOT_WRITE", System.currentTimeMillis() + 2)).isEqualTo(0);

        List<JoinTaskResult> after = mapper.selectResultsByTask(TEST_TASK_ID);
        assertThat(after.get(0).getStatus()).isEqualTo("FAILED");
        assertThat(after.get(0).getReason()).isEqualTo("JOIN_FAILED");
        assertThat(after.get(1).getStatus()).isEqualTo("SUCCESS");
        assertThat(after.get(1).getReason()).isEqualTo("");
    }
}
