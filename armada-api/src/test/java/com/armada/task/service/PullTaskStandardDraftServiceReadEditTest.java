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
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.vo.PullTaskStandardDraftVO;
import com.armada.task.model.vo.PullTaskStandardExecutionRowVO;
import com.armada.task.service.impl.PullTaskStandardDraftServiceImpl;
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

/** 草稿回读与编辑编排的 H2 集成测试。 */
@SpringJUnitConfig(PullTaskStandardDraftServiceReadEditTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskStandardDraftServiceReadEditTest {

    private static final long CREATOR = 501L;
    private static final long OTHER_CREATOR = 602L;
    private static final String LINK_A = "chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA";
    private static final String LINK_B = "chat.whatsapp.com/BBBBBBBBBBBBBBBBBBBBBB";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskStandardDraftService service;

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
    void currentReturnsEmptyViewWhenUserHasNoDraftYet() {
        PullTaskStandardDraftVO view = service.current(CREATOR);

        // 首次打开创建页是正常状态，不是 404。
        assertThat(view.draftTaskId()).isNull();
        assertThat(view.rows()).isEmpty();
        assertThat(view.linkLines()).isEmpty();
        assertThat(view.fileResults()).isEmpty();
        assertThat(view.matchedCount()).isZero();
        assertThat(view.remainingLinkCount()).isZero();
        assertThat(view.ignoredFileCount()).isZero();
    }

    @Test
    void currentReturnsRowsWithStatisticsAndVersion() {
        long taskId = seedTwoRows();

        PullTaskStandardDraftVO view = service.current(CREATOR);

        assertThat(view.draftTaskId()).isEqualTo(taskId);
        assertThat(view.version()).isEqualTo(1);
        assertThat(view.matchedCount()).isEqualTo(2);
        assertThat(view.rows()).extracting(
                        PullTaskStandardExecutionRowVO::seq,
                        PullTaskStandardExecutionRowVO::normalizedLink,
                        PullTaskStandardExecutionRowVO::sourceFileName,
                        PullTaskStandardExecutionRowVO::validMemberCount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, LINK_A, "a.txt", 1),
                        org.assertj.core.groups.Tuple.tuple(2, LINK_B, "b.txt", 1));
        // 链接文本不落库，回读时逐行结果必然为空，由前端从 sessionStorage 恢复。
        assertThat(view.linkLines()).isEmpty();
    }

    @Test
    void currentIsScopedToTheCreator() {
        seedTwoRows();

        assertThat(service.current(OTHER_CREATOR).draftTaskId()).isNull();
    }

    @Test
    void removeRowDropsTheRowAndItsMembersAndReturnsFreshView() {
        long taskId = seedTwoRows();
        long removedId = executionMapper.selectByTaskId(taskId).get(0).getId();

        PullTaskStandardDraftVO view = service.removeRow(removedId, CREATOR);

        assertThat(view.matchedCount()).isEqualTo(1);
        assertThat(view.rows()).extracting(PullTaskStandardExecutionRowVO::seq).containsExactly(2);
        assertThat(materialMapper.selectByExecution(removedId)).isEmpty();
    }

    @Test
    void removeRowRejectsRowBelongingToAnotherUsersDraft() {
        long taskId = seedTwoRows();
        long rowId = executionMapper.selectByTaskId(taskId).get(0).getId();

        assertThatThrownBy(() -> service.removeRow(rowId, OTHER_CREATOR))
                .isInstanceOf(BusinessException.class);
        assertThat(executionMapper.selectByTaskId(taskId)).hasSize(2);
    }

    @Test
    void clearRemovesEveryRowButKeepsTheDraftForReuse() {
        long taskId = seedTwoRows();

        PullTaskStandardDraftVO view = service.clear(CREATOR);

        assertThat(view.draftTaskId()).isEqualTo(taskId);
        assertThat(view.rows()).isEmpty();
        assertThat(view.matchedCount()).isZero();
    }

    @Test
    void clearOnMissingDraftIsRejected() {
        assertThatThrownBy(() -> service.clear(CREATOR)).isInstanceOf(BusinessException.class);
    }

    private long seedTwoRows() {
        long taskId = writer.ensureDraft(CREATOR, "运营甲", 100L).getId();
        writer.append(taskId, List.of(
                appendRow(1, LINK_A, "a.txt", 1, "8613800138001"),
                appendRow(2, LINK_B, "b.txt", 2, "8613800138002")), 200L);
        return taskId;
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
            return PullTaskNormalLinkH2Support.dataSource("pull_task_draft_read_edit_test");
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

        @Bean
        PullTaskStandardDraftService draftService(PullTaskMapper pullTaskMapper,
                                                  PullTaskGroupExecutionMapper executionMapper,
                                                  PullTaskStandardDraftWriter writer) {
            return new PullTaskStandardDraftServiceImpl(pullTaskMapper, executionMapper, writer);
        }
    }
}
