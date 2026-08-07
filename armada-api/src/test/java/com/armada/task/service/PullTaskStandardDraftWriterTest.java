package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.service.impl.PullTaskStandardDraftWriter;
import com.armada.task.service.impl.PullTaskStandardDraftWriter.AppendRow;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 草稿事务写入组件的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(PullTaskStandardDraftWriterTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskStandardDraftWriterTest {

    private static final long CREATOR = 501L;
    private static final String LINK_A = "chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA";
    private static final String LINK_B = "chat.whatsapp.com/BBBBBBBBBBBBBBBBBBBBBB";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskStandardDraftWriter writer;

    @Autowired
    private PullTaskGroupExecutionMapper executionMapper;

    @Autowired
    private PullTaskMaterialMemberMapper materialMapper;

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
    void ensureDraftCreatesOnceAndReusesAfterwards() {
        PullTask first = writer.ensureDraft(CREATOR, "运营甲", 100L);
        PullTask second = writer.ensureDraft(CREATOR, "运营甲", 200L);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void ensureDraftIsPerCreator() {
        PullTask mine = writer.ensureDraft(CREATOR, "运营甲", 100L);
        PullTask others = writer.ensureDraft(602L, "运营乙", 100L);

        assertThat(others.getId()).isNotEqualTo(mine.getId());
    }

    @Test
    void appendWritesExecutionRowsAndTheirMembers() {
        long taskId = writer.ensureDraft(CREATOR, "运营甲", 100L).getId();

        writer.append(taskId, List.of(appendRow(1, LINK_A, "a.txt", 0, "8613800138001")), 300L);

        List<PullTaskGroupExecution> rows = executionMapper.selectByTaskId(taskId);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getSeq()).isEqualTo(1);
            assertThat(row.getNormalizedLink()).isEqualTo(LINK_A);
            // 草稿期不占链接：生成列在 execution_status=0 时为 NULL。
            assertThat(row.getExecutionStatus()).isZero();
            assertThat(row.getStage()).isEqualTo(PullTaskExecutionStage.MANAGER_JOIN.code());
        });
        assertThat(materialMapper.selectByExecution(rows.get(0).getId()))
                .extracting(PullTaskMaterialMember::getNormalizedPhone)
                .containsExactly("8613800138001");
    }

    @Test
    void appendIsIncrementalAndLeavesEarlierRowsUntouched() {
        long taskId = writer.ensureDraft(CREATOR, "运营甲", 100L).getId();
        writer.append(taskId, List.of(appendRow(1, LINK_A, "a.txt", 0, "8613800138001")), 300L);

        writer.append(taskId, List.of(appendRow(2, LINK_B, "b.txt", 1, "8613800138002")), 400L);

        assertThat(executionMapper.selectByTaskId(taskId))
                .extracting(PullTaskGroupExecution::getSeq,
                        PullTaskGroupExecution::getNormalizedLink)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, LINK_A),
                        org.assertj.core.groups.Tuple.tuple(2, LINK_B));
    }

    @Test
    void appendAcceptsEmptyBatchWithoutTouchingAnything() {
        long taskId = writer.ensureDraft(CREATOR, "运营甲", 100L).getId();

        writer.append(taskId, List.of(), 300L);

        assertThat(executionMapper.selectByTaskId(taskId)).isEmpty();
    }

    @Test
    void removeRowDeletesTheRowAndItsMembers() {
        long taskId = writer.ensureDraft(CREATOR, "运营甲", 100L).getId();
        writer.append(taskId, List.of(
                appendRow(1, LINK_A, "a.txt", 0, "8613800138001"),
                appendRow(2, LINK_B, "b.txt", 1, "8613800138002")), 300L);
        long removedId = executionMapper.selectByTaskId(taskId).get(0).getId();

        writer.removeRow(taskId, removedId);

        assertThat(executionMapper.selectByTaskId(taskId))
                .extracting(PullTaskGroupExecution::getSeq).containsExactly(2);
        assertThat(materialMapper.selectByExecution(removedId)).isEmpty();
    }

    @Test
    void removeRowRollsBackWhenRowIsAlreadyFrozen() {
        long taskId = writer.ensureDraft(CREATOR, "运营甲", 100L).getId();
        writer.append(taskId, List.of(appendRow(1, LINK_A, "a.txt", 0, "8613800138001")), 300L);
        long rowId = executionMapper.selectByTaskId(taskId).get(0).getId();
        executionMapper.freezeDraftRows(taskId, 500L);

        assertThatThrownBy(() -> writer.removeRow(taskId, rowId))
                .isInstanceOf(BusinessException.class);

        // 料子先删、执行行后删；执行行删不掉时整笔回滚，料子必须还在。
        assertThat(materialMapper.selectByExecution(rowId)).hasSize(1);
        assertThat(executionMapper.selectByTaskId(taskId)).hasSize(1);
    }

    @Test
    void clearAllRemovesEveryRowAndMemberButKeepsTheDraftTask() {
        long taskId = writer.ensureDraft(CREATOR, "运营甲", 100L).getId();
        writer.append(taskId, List.of(
                appendRow(1, LINK_A, "a.txt", 0, "8613800138001"),
                appendRow(2, LINK_B, "b.txt", 1, "8613800138002")), 300L);
        List<Long> rowIds = executionMapper.selectByTaskId(taskId).stream()
                .map(PullTaskGroupExecution::getId).toList();

        writer.clearAll(taskId);

        assertThat(executionMapper.selectByTaskId(taskId)).isEmpty();
        rowIds.forEach(id -> assertThat(materialMapper.selectByExecution(id)).isEmpty());
        // 草稿任务行保留下来复用，不是每次清空都换一条。
        assertThat(writer.ensureDraft(CREATOR, "运营甲", 600L).getId()).isEqualTo(taskId);
    }

    private static AppendRow appendRow(int seq, String link, String fileName,
                                       int fileIndex, String phone) {
        PullTaskGroupExecution execution = new PullTaskGroupExecution();
        execution.setSeq(seq);
        execution.setNormalizedLink(link);
        execution.setInviteCode(link.substring(link.lastIndexOf('/') + 1));
        execution.setSourceLinkLineNo(seq);
        execution.setSourceFileIndex(fileIndex);
        execution.setSourceFileName(fileName);
        execution.setTotalLineCount(1);
        execution.setValidMemberCount(1);
        execution.setInvalidLineCount(0);
        execution.setDuplicateLineCount(0);

        PullTaskMaterialMember member = new PullTaskMaterialMember();
        member.setMemberSeq(1);
        member.setSourceLineNo(1);
        member.setNormalizedPhone(phone);
        member.setAdminRequired(0);
        return new AppendRow(execution, List.of(member));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_draft_writer_test");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskMaterialMemberMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskMapper pullTaskMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMapper.class);
        }

        @Bean
        PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean
        PullTaskMaterialMemberMapper materialMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMaterialMemberMapper.class);
        }

        @Bean
        PullTaskStandardDraftWriter writer(PullTaskMapper pullTaskMapper,
                                           PullTaskGroupExecutionMapper executionMapper,
                                           PullTaskMaterialMemberMapper materialMapper) {
            return new PullTaskStandardDraftWriter(pullTaskMapper, executionMapper, materialMapper);
        }
    }
}
