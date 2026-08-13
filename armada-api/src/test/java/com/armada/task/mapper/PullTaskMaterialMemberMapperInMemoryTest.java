package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskFactStatusCriteria;
import com.armada.task.model.dto.PullTaskFactResult;
import com.armada.task.model.dto.PullTaskFactTransition;
import com.armada.task.model.dto.PullTaskMaterialPullResult;
import com.armada.task.model.dto.PullTaskParticipantAggregateTransition;
import com.armada.task.model.dto.PullTaskParticipantAttemptBinding;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.enums.PullTaskMaterialAdminStatus;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 料子成员 Mapper 的 H2 MySQL 模式测试：游标语义、单文件去重与回调定位。 */
@SpringJUnitConfig(PullTaskMaterialMemberMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskMaterialMemberMapperInMemoryTest {

    private static final long EXECUTION = 500L;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskMaterialMemberMapper mapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void batchInsertPersistsAllMembersWithStableOrder() {
        mapper.batchInsert(List.of(
                member(1, "8613800000001", 0),
                member(2, "8613800000002", 1),
                member(3, "8613800000003", 0)));

        List<PullTaskMaterialMember> unconsumed = mapper.selectUnconsumed(EXECUTION, 10);
        assertThat(unconsumed).extracting(PullTaskMaterialMember::getMemberSeq)
                .containsExactly(1, 2, 3);
        assertThat(unconsumed.get(1).getAdminRequired()).isEqualTo(1);
        // sourceLineNo 与 normalizedPhone 各自回读，避免 INSERT 列清单里位置错位。
        // sourceLineNo 用 seq * 10 + 3 构造，与 memberSeq 恒不相等：两者是相邻同类型的
        // INT 列，若 fixture 让它们总是相等，二者互换也能"回读正确"，测试就失去了意义。
        assertThat(unconsumed.get(1).getMemberSeq()).isEqualTo(2);
        assertThat(unconsumed.get(1).getSourceLineNo()).isEqualTo(23);
        assertThat(unconsumed.get(1).getNormalizedPhone()).isEqualTo("8613800000002");
    }

    @Test
    void duplicatePhoneWithinOneExecutionIsRejected() {
        mapper.batchInsert(List.of(member(1, "8613800000001", 0)));

        assertThatThrownBy(() -> mapper.batchInsert(List.of(member(2, "8613800000001", 0))))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void assignToCallConsumesMembersAndAdvancesTheCursor() {
        mapper.batchInsert(List.of(
                member(1, "8613800000001", 0),
                member(2, "8613800000002", 0),
                member(3, "8613800000003", 0)));

        List<Long> firstBatch = mapper.selectUnconsumed(EXECUTION, 2).stream()
                .map(PullTaskMaterialMember::getId).toList();
        assertThat(mapper.assignToCall(firstBatch, 900L, 900L)).isEqualTo(2);

        // pull_call_id 非空即"已消费"，游标自然前移，不需要单独的游标列。
        assertThat(mapper.selectUnconsumed(EXECUTION, 10))
                .extracting(PullTaskMaterialMember::getMemberSeq)
                .containsExactly(3);
    }

    @Test
    void alreadyConsumedMembersAreNotReassigned() {
        mapper.batchInsert(List.of(member(1, "8613800000001", 0)));
        Long id = mapper.selectUnconsumed(EXECUTION, 1).get(0).getId();

        assertThat(mapper.assignToCall(List.of(id), 900L, 900L)).isEqualTo(1);
        // 重复分配必须是 0 行：一个料子一生只属于一次调用。
        assertThat(mapper.assignToCall(List.of(id), 901L, 901L)).isZero();
    }

    @Test
    void pullResultWriteBackKeepsUnknownDistinctFromFailure() {
        mapper.batchInsert(List.of(
                member(1, "8613800000001", 0),
                member(2, "8613800000002", 0)));
        List<PullTaskMaterialMember> rows = mapper.selectUnconsumed(EXECUTION, 10);
        mapper.assignToCall(rows.stream().map(PullTaskMaterialMember::getId).toList(), 900L, 900L);

        mapper.writeBackPullResult(new PullTaskMaterialPullResult(
                rows.get(0).getId(), PullTaskMaterialPullStatus.SUCCESS.code(),
                PullTaskFactResult.success("8613800000001@s.whatsapp.net", 950L), 950L));
        mapper.writeBackPullResult(new PullTaskMaterialPullResult(
                rows.get(1).getId(), PullTaskMaterialPullStatus.UNKNOWN.code(),
                PullTaskFactResult.reason("TIMEOUT", "协议超时"), 950L));

        List<PullTaskMaterialMember> after = mapper.selectByExecution(EXECUTION);
        assertThat(after.get(0).getPullStatus()).isEqualTo(PullTaskMaterialPullStatus.SUCCESS.code());
        assertThat(after.get(0).getWaJid()).isEqualTo("8613800000001@s.whatsapp.net");
        // 成功行的原因码/原因描述必须保持 null，不能被另一行的字段串位。
        assertThat(after.get(0).getPullReasonCode()).isNull();
        assertThat(after.get(0).getPullReasonMessage()).isNull();
        assertThat(after.get(0).getPullResultAt()).isEqualTo(950L);

        assertThat(after.get(1).getPullStatus()).isEqualTo(PullTaskMaterialPullStatus.UNKNOWN.code());
        assertThat(after.get(1).getPullReasonCode()).isEqualTo("TIMEOUT");
        // reasonMessage 与 waJid 分别断言，避免二者在 UPDATE 里被写反。
        assertThat(after.get(1).getPullReasonMessage()).isEqualTo("协议超时");
        assertThat(after.get(1).getWaJid()).isNull();
        assertThat(after.get(1).getPullResultAt()).isEqualTo(950L);
    }

    @Test
    void pendingAdminOnlyIncludesFlaggedMembersThatJoinedSuccessfully() {
        mapper.batchInsert(List.of(
                member(1, "8613800000001", 1),
                member(2, "8613800000002", 1),
                member(3, "8613800000003", 0)));
        List<PullTaskMaterialMember> rows = mapper.selectUnconsumed(EXECUTION, 10);
        mapper.assignToCall(rows.stream().map(PullTaskMaterialMember::getId).toList(), 900L, 900L);

        mapper.writeBackPullResult(new PullTaskMaterialPullResult(
                rows.get(0).getId(), PullTaskMaterialPullStatus.SUCCESS.code(),
                PullTaskFactResult.success("jid1", 950L), 950L));
        mapper.writeBackPullResult(new PullTaskMaterialPullResult(
                rows.get(1).getId(), PullTaskMaterialPullStatus.FAILED.code(),
                PullTaskFactResult.reason("PRIVACY", "隐私限制"), 950L));
        mapper.writeBackPullResult(new PullTaskMaterialPullResult(
                rows.get(2).getId(), PullTaskMaterialPullStatus.SUCCESS.code(),
                PullTaskFactResult.success("jid3", 950L), 950L));

        // 入群失败或结果未知的标记料子不提权；未标记的成功料子也不提权。
        assertThat(mapper.selectPendingAdmin(
                EXECUTION, 1, PullTaskMaterialPullStatus.SUCCESS.code(),
                PullTaskMaterialAdminStatus.PENDING.code()))
                .extracting(PullTaskMaterialMember::getNormalizedPhone)
                .containsExactly("8613800000001");
    }

    @Test
    void adminCallbackIsLocatedByCommandId() {
        mapper.batchInsert(List.of(member(1, "8613800000001", 1)));
        Long id = mapper.selectUnconsumed(EXECUTION, 1).get(0).getId();
        mapper.assignToCall(List.of(id), 900L, 900L);
        mapper.writeBackPullResult(new PullTaskMaterialPullResult(
                id, PullTaskMaterialPullStatus.SUCCESS.code(),
                PullTaskFactResult.success("jid1", 950L), 950L));

        mapper.markAdminSubmitted(
                id, PullTaskMaterialAdminStatus.PENDING.code(),
                PullTaskMaterialAdminStatus.SUBMITTED.code(), "cmd-admin-1", 960L);

        PullTaskMaterialMember found = mapper.selectByAdminCommandId("cmd-admin-1");
        assertThat(found).isNotNull();
        assertThat(found.getId()).isEqualTo(id);
        assertThat(found.getAdminStatus())
                .isEqualTo(PullTaskMaterialAdminStatus.SUBMITTED.code());
        // 命令 ID 本身也要原样回读，避免和 pull 结果块的字段串位。
        assertThat(found.getAdminCommandId()).isEqualTo("cmd-admin-1");
    }

    @Test
    void adminResultUsesExpectedStatusCasAndCannotBeOverwritten() {
        mapper.batchInsert(List.of(member(1, "8613800000001", 1)));
        Long id = mapper.selectUnconsumed(EXECUTION, 1).get(0).getId();
        mapper.assignToCall(List.of(id), 900L, 900L);
        mapper.writeBackPullResult(new PullTaskMaterialPullResult(
                id, PullTaskMaterialPullStatus.SUCCESS.code(),
                PullTaskFactResult.success("jid1", 950L), 950L));
        mapper.markAdminSubmitted(
                id, PullTaskMaterialAdminStatus.PENDING.code(),
                PullTaskMaterialAdminStatus.SUBMITTED.code(), "cmd-admin-1", 960L);

        assertThat(mapper.writeBackAdminResult(
                id, PullTaskMaterialAdminStatus.SUBMITTED.code(),
                PullTaskMaterialAdminStatus.SUCCESS.code(), null, 970L)).isEqualTo(1);
        assertThat(mapper.writeBackAdminResult(
                id, PullTaskMaterialAdminStatus.SUBMITTED.code(),
                PullTaskMaterialAdminStatus.FAILED.code(), "LATE_FAILURE", 980L)).isZero();

        PullTaskMaterialMember saved = mapper.selectByExecution(EXECUTION).get(0);
        assertThat(saved.getAdminStatus()).isEqualTo(PullTaskMaterialAdminStatus.SUCCESS.code());
        assertThat(saved.getAdminReasonCode()).isNull();
        assertThat(saved.getAdminResultAt()).isEqualTo(970L);
    }

    @Test
    void submittedAndUnknownPullFactsConvergeByCasAndAreCountedPerCall() {
        mapper.batchInsert(List.of(member(1, "8613800000001", 1)));
        Long id = mapper.selectUnconsumed(EXECUTION, 1).get(0).getId();
        mapper.assignToCall(List.of(id), 900L, 900L);
        List<Integer> open = List.of(PullTaskMaterialPullStatus.SUBMITTED.code(),
                PullTaskMaterialPullStatus.UNKNOWN.code());
        PullTaskFactStatusCriteria criteria = new PullTaskFactStatusCriteria(900L, open);
        assertThat(mapper.countByPullCallAndStatuses(criteria)).isEqualTo(1);

        assertThat(mapper.transitionPullResult(new PullTaskFactTransition(
                id, List.of(PullTaskMaterialPullStatus.SUBMITTED.code()),
                PullTaskMaterialPullStatus.UNKNOWN.code(),
                PullTaskFactResult.reason("TIMEOUT", "协议超时"), 950L))).isEqualTo(1);
        assertThat(mapper.transitionPullResult(new PullTaskFactTransition(
                id, open, PullTaskMaterialPullStatus.SUCCESS.code(),
                PullTaskFactResult.success(
                        "8613800000001@s.whatsapp.net", 980L), 980L)))
                .isEqualTo(1);
        assertThat(mapper.countByPullCallAndStatuses(criteria)).isZero();
        PullTaskMaterialMember saved = mapper.selectByExecution(EXECUTION).get(0);
        assertThat(saved.getPullStatus()).isEqualTo(PullTaskMaterialPullStatus.SUCCESS.code());
        assertThat(saved.getWaJid()).isEqualTo("8613800000001@s.whatsapp.net");

        mapper.markAdminSubmitted(id, PullTaskMaterialAdminStatus.PENDING.code(),
                PullTaskMaterialAdminStatus.SUBMITTED.code(), "cmd-admin", 990L);
        assertThat(mapper.transitionAdminResult(new PullTaskFactTransition(
                id, List.of(PullTaskMaterialAdminStatus.SUBMITTED.code(),
                        PullTaskMaterialAdminStatus.UNKNOWN.code()),
                PullTaskMaterialAdminStatus.SUCCESS.code(),
                PullTaskFactResult.success(saved.getWaJid(), 1_000L), 1_000L)))
                .isEqualTo(1);
        assertThat(mapper.selectByExecution(EXECUTION).get(0).getAdminStatus())
                .isEqualTo(PullTaskMaterialAdminStatus.SUCCESS.code());
    }

    @Test
    void otherTenantMembersAreInvisible() {
        mapper.batchInsert(List.of(member(1, "8613800000001", 0)));

        TenantContext.set(8L);
        assertThat(mapper.selectUnconsumed(EXECUTION, 10)).isEmpty();
        assertThat(mapper.selectByAdminCommandId("cmd-admin-1")).isNull();
    }

    @Test
    void attemptBindingAndFourExplicitFailuresUseCasAndRetryLimit() {
        mapper.batchInsert(List.of(member(1, "8613800000001", 0)));
        PullTaskMaterialMember material = mapper.selectUnconsumed(EXECUTION, 1).get(0);
        long priorAttemptId = 0L;

        for (int failure = 1; failure <= 4; failure++) {
            long attemptId = 1_000L + failure;
            long callId = 2_000L + failure;
            assertThat(mapper.bindPullAttempt(new PullTaskParticipantAttemptBinding(
                    material.getId(), attemptId, callId, 701L, 100L + failure)))
                    .isEqualTo(1);
            assertThat(mapper.markPullAttemptSubmitted(new PullTaskParticipantAttemptBinding(
                    material.getId(), attemptId, callId, 701L, 100L + failure)))
                    .isEqualTo(1);
            assertThat(mapper.bindPullAttempt(new PullTaskParticipantAttemptBinding(
                    material.getId(), attemptId + 100L, callId + 100L, 702L, 150L + failure)))
                    .isZero();
            if (priorAttemptId > 0) {
                assertThat(mapper.transitionPullAttempt(materialTransition(
                        material.getId(), priorAttemptId,
                        new PullTaskParticipantAggregateTransition.Expected(
                                List.of(PullTaskMaterialPullStatus.SUBMITTED.code()), failure - 1L),
                        new PullTaskParticipantAggregateTransition.Target(
                                PullTaskMaterialPullStatus.UNCONSUMED.code(), failure, null, null))))
                        .isZero();
            }
            int targetStatus = failure < 4
                    ? PullTaskMaterialPullStatus.UNCONSUMED.code()
                    : PullTaskMaterialPullStatus.FAILED.code();
            Long targetCallId = failure < 4 ? null : callId;
            assertThat(mapper.transitionPullAttempt(materialTransition(
                    material.getId(), attemptId,
                    new PullTaskParticipantAggregateTransition.Expected(
                            List.of(PullTaskMaterialPullStatus.SUBMITTED.code()), failure - 1L),
                    new PullTaskParticipantAggregateTransition.Target(
                            targetStatus, failure, targetCallId, null)))).isEqualTo(1);
            priorAttemptId = attemptId;
            if (failure < 4) {
                PullTaskMaterialMember saved = mapper.selectUnconsumed(EXECUTION, 10).get(0);
                assertThat(saved.getPullFailureCount()).isEqualTo((long) failure);
                assertThat(saved.getPullCallId()).isNull();
                assertThat(saved.getActivePullAttemptId()).isNull();
            }
        }

        assertThat(mapper.selectUnconsumed(EXECUTION, 10)).isEmpty();
        assertThat(mapper.selectByExecution(EXECUTION)).singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getPullStatus()).isEqualTo(PullTaskMaterialPullStatus.FAILED.code());
                    assertThat(saved.getPullFailureCount()).isEqualTo(4L);
                });
    }

    @Test
    void unknownReleaseReturnsToPoolWithoutConsumingFailureCount() {
        mapper.batchInsert(List.of(member(1, "8613800000001", 0)));
        PullTaskMaterialMember material = mapper.selectUnconsumed(EXECUTION, 1).get(0);
        mapper.bindPullAttempt(new PullTaskParticipantAttemptBinding(
                material.getId(), 1_001L, 2_001L, 701L, 100L));
        mapper.markPullAttemptSubmitted(new PullTaskParticipantAttemptBinding(
                material.getId(), 1_001L, 2_001L, 701L, 100L));

        assertThat(mapper.transitionPullAttempt(materialTransition(
                material.getId(), 1_001L,
                new PullTaskParticipantAggregateTransition.Expected(
                        List.of(PullTaskMaterialPullStatus.SUBMITTED.code()), 0L),
                new PullTaskParticipantAggregateTransition.Target(
                        PullTaskMaterialPullStatus.UNCONSUMED.code(), 0L, null, null))))
                .isEqualTo(1);

        assertThat(mapper.selectUnconsumed(EXECUTION, 1)).singleElement()
                .satisfies(saved -> {
                    assertThat(saved.getPullFailureCount()).isZero();
                    assertThat(saved.getPullCallId()).isNull();
                    assertThat(saved.getActivePullAttemptId()).isNull();
                });
    }

    @Test
    void successIsMonotonicAndLateSuccessPreservesNewerActiveAttempt() {
        mapper.batchInsert(List.of(
                member(1, "8613800000001", 0),
                member(2, "8613800000002", 0)));
        List<PullTaskMaterialMember> rows = mapper.selectUnconsumed(EXECUTION, 2);
        PullTaskMaterialMember direct = rows.get(0);
        mapper.bindPullAttempt(new PullTaskParticipantAttemptBinding(
                direct.getId(), 1_001L, 2_001L, 701L, 100L));
        mapper.markPullAttemptSubmitted(new PullTaskParticipantAttemptBinding(
                direct.getId(), 1_001L, 2_001L, 701L, 100L));
        PullTaskParticipantAggregateTransition success = materialTransition(
                direct.getId(), 1_001L,
                new PullTaskParticipantAggregateTransition.Expected(
                        List.of(PullTaskMaterialPullStatus.SUBMITTED.code()), 0L),
                new PullTaskParticipantAggregateTransition.Target(
                        PullTaskMaterialPullStatus.SUCCESS.code(), 0L, 2_001L, null));
        assertThat(mapper.promotePullSuccess(success)).isEqualTo(1);
        assertThat(mapper.transitionPullAttempt(materialTransition(
                direct.getId(), 1_001L,
                new PullTaskParticipantAggregateTransition.Expected(
                        List.of(PullTaskMaterialPullStatus.SUCCESS.code()), 0L),
                new PullTaskParticipantAggregateTransition.Target(
                        PullTaskMaterialPullStatus.FAILED.code(), 1L, 2_001L, null))))
                .isZero();

        PullTaskMaterialMember late = rows.get(1);
        mapper.bindPullAttempt(new PullTaskParticipantAttemptBinding(
                late.getId(), 1_010L, 2_010L, 701L, 110L));
        mapper.markPullAttemptSubmitted(new PullTaskParticipantAttemptBinding(
                late.getId(), 1_010L, 2_010L, 701L, 110L));
        mapper.transitionPullAttempt(materialTransition(
                late.getId(), 1_010L,
                new PullTaskParticipantAggregateTransition.Expected(
                        List.of(PullTaskMaterialPullStatus.SUBMITTED.code()), 0L),
                new PullTaskParticipantAggregateTransition.Target(
                        PullTaskMaterialPullStatus.UNCONSUMED.code(), 0L, null, null)));
        mapper.bindPullAttempt(new PullTaskParticipantAttemptBinding(
                late.getId(), 1_011L, 2_011L, 702L, 120L));

        assertThat(mapper.promotePullSuccess(materialTransition(
                late.getId(), 1_010L,
                new PullTaskParticipantAggregateTransition.Expected(
                        List.of(PullTaskMaterialPullStatus.UNCONSUMED.code()), 0L),
                new PullTaskParticipantAggregateTransition.Target(
                        PullTaskMaterialPullStatus.SUCCESS.code(), 0L, 2_010L, null))))
                .isEqualTo(1);
        assertThat(mapper.selectByExecution(EXECUTION).get(1))
                .satisfies(saved -> {
                    assertThat(saved.getPullStatus()).isEqualTo(PullTaskMaterialPullStatus.SUCCESS.code());
                    assertThat(saved.getPullCallId()).isEqualTo(2_010L);
                    assertThat(saved.getActivePullAttemptId()).isEqualTo(1_011L);
                });
    }

    private PullTaskParticipantAggregateTransition materialTransition(
            long participantId,
            long attemptId,
            PullTaskParticipantAggregateTransition.Expected expected,
            PullTaskParticipantAggregateTransition.Target target) {
        return new PullTaskParticipantAggregateTransition(
                new PullTaskParticipantAggregateTransition.Scope(participantId, attemptId, 500L),
                expected,
                target,
                PullTaskFactResult.success(TARGET_JID, 500L));
    }

    private static final String TARGET_JID = "8613800000001@s.whatsapp.net";

    private PullTaskMaterialMember member(int seq, String phone, int adminRequired) {
        PullTaskMaterialMember row = new PullTaskMaterialMember();
        row.setGroupExecutionId(EXECUTION);
        row.setMemberSeq(seq);
        // 与 seq 保持非线性、恒不相等的关系：真实数据里 sourceLineNo(首次出现的原始行号)
        // 一旦文件里有空行/非法号码/重复号码就会与 memberSeq(去重后顺序)分道扬镳；
        // 若 fixture 让两者恒等，INSERT 列清单里这两个相邻 INT 列的换位 bug 就无法被
        // 任何断言捕获。
        row.setSourceLineNo(seq * 10 + 3);
        row.setNormalizedPhone(phone);
        row.setAdminRequired(adminRequired);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_material_member_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskMaterialMemberMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskMaterialMemberMapper pullTaskMaterialMemberMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskMaterialMemberMapper.class);
        }
    }
}
