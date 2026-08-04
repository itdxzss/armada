package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
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
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

/** 草稿执行行与料子成员编辑操作的 H2 MySQL 模式测试。 */
@SpringJUnitConfig(PullTaskDraftEditMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskDraftEditMapperInMemoryTest {

    private static final long TASK_A = 1L;
    private static final long TASK_B = 2L;
    private static final String LINK_A = "chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA";
    private static final String LINK_B = "chat.whatsapp.com/BBBBBBBBBBBBBBBBBBBBBB";

    @Autowired
    private DataSource dataSource;

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
    void deleteDraftRowRemovesOnlyTheTargetRow() {
        PullTaskGroupExecution first = insertRow(TASK_A, 1, LINK_A, "a.txt", 0);
        insertRow(TASK_A, 2, LINK_B, "b.txt", 1);

        assertThat(executionMapper.deleteDraftRow(TASK_A, first.getId())).isEqualTo(1);

        assertThat(executionMapper.selectByTaskId(TASK_A))
                .extracting(PullTaskGroupExecution::getSeq).containsExactly(2);
    }

    @Test
    void deleteDraftRowRefusesFrozenRow() {
        PullTaskGroupExecution row = insertRow(TASK_A, 1, LINK_A, "a.txt", 0);
        executionMapper.freezeDraftRows(TASK_A, 900L);

        assertThat(executionMapper.deleteDraftRow(TASK_A, row.getId())).isZero();
        assertThat(executionMapper.selectByTaskId(TASK_A)).hasSize(1);
    }

    @Test
    void deleteDraftRowRefusesRowOfAnotherTask() {
        PullTaskGroupExecution row = insertRow(TASK_A, 1, LINK_A, "a.txt", 0);

        assertThat(executionMapper.deleteDraftRow(TASK_B, row.getId())).isZero();
    }

    @Test
    void selectOccupiedLinksReturnsOnlyFrozenOrRunningLinks() {
        insertRow(TASK_A, 1, LINK_A, "a.txt", 0);
        executionMapper.freezeDraftRows(TASK_A, 900L);
        insertRow(TASK_B, 1, LINK_B, "b.txt", 0);

        // LINK_A 已冻结(execution_status=1)进入占用；LINK_B 仍是草稿(0)不占用。
        assertThat(executionMapper.selectOccupiedLinks(List.of(LINK_A, LINK_B)))
                .containsExactly(LINK_A);
    }

    @Test
    void selectOccupiedLinksIsEmptyWhenNothingFrozen() {
        insertRow(TASK_A, 1, LINK_A, "a.txt", 0);

        assertThat(executionMapper.selectOccupiedLinks(List.of(LINK_A, LINK_B))).isEmpty();
    }

    @Test
    void deleteByExecutionRemovesOnlyThatExecutionMembers() {
        PullTaskGroupExecution kept = insertRow(TASK_A, 1, LINK_A, "a.txt", 0);
        PullTaskGroupExecution removed = insertRow(TASK_A, 2, LINK_B, "b.txt", 1);
        materialMapper.batchInsert(List.of(member(kept.getId(), 1, "8613800138001")));
        materialMapper.batchInsert(List.of(member(removed.getId(), 1, "8613800138002")));

        assertThat(materialMapper.deleteByExecution(removed.getId())).isEqualTo(1);

        assertThat(materialMapper.selectByExecution(removed.getId())).isEmpty();
        assertThat(materialMapper.selectByExecution(kept.getId())).hasSize(1);
    }

    @Test
    void otherTenantCannotDeleteOrSeeOccupancy() {
        PullTaskGroupExecution row = insertRow(TASK_A, 1, LINK_A, "a.txt", 0);
        executionMapper.freezeDraftRows(TASK_A, 900L);

        TenantContext.set(8L);
        assertThat(executionMapper.selectOccupiedLinks(List.of(LINK_A))).isEmpty();
        assertThat(executionMapper.deleteDraftRow(TASK_A, row.getId())).isZero();

        TenantContext.set(7L);
        assertThat(executionMapper.selectByTaskId(TASK_A)).hasSize(1);
    }

    private PullTaskGroupExecution insertRow(long taskId, int seq, String link,
                                             String fileName, int fileIndex) {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setTaskId(taskId);
        row.setSeq(seq);
        row.setNormalizedLink(link);
        row.setInviteCode(link.substring(link.lastIndexOf('/') + 1));
        row.setSourceLinkLineNo(seq);
        row.setSourceFileIndex(fileIndex);
        row.setSourceFileName(fileName);
        row.setTotalLineCount(1);
        row.setValidMemberCount(1);
        row.setInvalidLineCount(0);
        row.setDuplicateLineCount(0);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        executionMapper.insertDraft(row);
        return row;
    }

    private static PullTaskMaterialMember member(long executionId, int seq, String phone) {
        PullTaskMaterialMember row = new PullTaskMaterialMember();
        row.setGroupExecutionId(executionId);
        row.setMemberSeq(seq);
        row.setSourceLineNo(seq);
        row.setNormalizedPhone(phone);
        row.setAdminRequired(0);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_draft_edit_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(dataSource, interceptor,
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskMaterialMemberMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean
        PullTaskMaterialMemberMapper materialMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMaterialMemberMapper.class);
        }
    }
}
