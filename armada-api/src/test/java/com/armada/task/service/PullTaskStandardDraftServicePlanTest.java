package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.service.GroupFolderService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.enums.PullTaskCreationMode;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskStandardLinkLineStatus;
import com.armada.task.model.vo.PullTaskStandardDraftVO;
import com.armada.task.model.vo.PullTaskStandardExecutionRowVO;
import com.armada.task.model.vo.PullTaskStandardFileResultVO;
import com.armada.task.model.vo.PullTaskStandardLinkLineVO;
import com.armada.task.service.impl.PullTaskStandardDraftServiceImpl;
import com.armada.task.service.impl.PullTaskStandardDraftWriter;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.nio.charset.StandardCharsets;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.multipart.MultipartFile;

/** 创建页本地匹配追加流程的 H2 集成测试。 */
@SpringJUnitConfig(PullTaskStandardDraftServicePlanTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskStandardDraftServicePlanTest {

    private static final long CREATOR = 501L;
    private static final String OPERATOR = "运营甲";
    private static final String LINK_A = "chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA";
    private static final String LINK_B = "chat.whatsapp.com/BBBBBBBBBBBBBBBBBBBBBB";
    private static final String LINK_C = "chat.whatsapp.com/CCCCCCCCCCCCCCCCCCCCCC";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskStandardDraftService service;

    @Autowired
    private GroupFolderService groupFolderService;

    @Autowired
    private PullTaskGroupExecutionMapper executionMapper;

    @Autowired
    private PullTaskMaterialMemberMapper materialMapper;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContext.set(7L);
        DataScopeContext.open(DataScope.self(CREATOR));
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        org.mockito.Mockito.reset(groupFolderService);
    }

    @AfterEach
    void tearDown() {
        DataScopeContext.clear();
        TenantContext.clear();
    }

    @Test
    void createsOneRowPerMatchAndReportsRemainingLinks() {
        PullTaskStandardDraftVO view = linkPlan(
                null, LINK_A + "\n" + LINK_B,
                List.of(txt("a.txt", "8613800138001\n")), CREATOR, OPERATOR);

        assertThat(view.draftTaskId()).isNotNull();
        assertThat(view.creationMode()).isEqualTo(PullTaskCreationMode.PASTED_LINK);
        assertThat(view.matchedCount()).isEqualTo(1);
        assertThat(view.remainingLinkCount()).isEqualTo(1);
        assertThat(view.ignoredFileCount()).isZero();
        assertThat(view.rows()).singleElement()
                .satisfies(row -> assertThat(row.sourceFileName()).isEqualTo("a.txt"));
    }

    @Test
    void newGroupModeCreatesOneExecutionPerAcceptedTxtWithoutLinks() {
        PullTaskStandardDraftVO view = service.plan(
                PullTaskCreationMode.NEW_GROUP, null, null,
                List.of(txt("a.txt", "8613800138001\n"),
                        txt("b.txt", "8613800138002\n")),
                CREATOR, OPERATOR);

        assertThat(view.matchedCount()).isEqualTo(2);
        assertThat(view.creationMode()).isEqualTo(PullTaskCreationMode.NEW_GROUP);
        assertThat(view.remainingLinkCount()).isZero();
        assertThat(view.ignoredFileCount()).isZero();
        assertThat(executionMapper.selectByTaskId(view.draftTaskId()))
                .allSatisfy(row -> {
                    assertThat(row.getNormalizedLink()).isNull();
                    assertThat(row.getInviteCode()).isNull();
                    assertThat(row.getSourceLinkLineNo()).isNull();
                    assertThat(row.getStage()).isEqualTo(PullTaskExecutionStage.GROUP_CREATE.code());
                })
                .extracting(row -> row.getSourceFileName())
                .containsExactly("a.txt", "b.txt");
    }

    @Test
    void resourcePoolModeCreatesOneUnboundExecutionPerAcceptedTxt() {
        PullTaskStandardDraftVO view = service.plan(
                PullTaskCreationMode.RESOURCE_POOL, 18L, null,
                List.of(txt("a.txt", "8613800138001\n"),
                        txt("b.txt", "8613800138002\n")),
                CREATOR, OPERATOR);

        assertThat(view.creationMode()).isEqualTo(PullTaskCreationMode.RESOURCE_POOL);
        assertThat(view.matchedCount()).isEqualTo(2);
        assertThat(view.linkLines()).isEmpty();
        assertThat(executionMapper.selectByTaskId(view.draftTaskId()))
                .allSatisfy(row -> {
                    assertThat(row.getGroupLinkId()).isNull();
                    assertThat(row.getGroupJid()).isNull();
                    assertThat(row.getNormalizedLink()).isNull();
                    assertThat(row.getStage()).isEqualTo(
                            PullTaskExecutionStage.MANAGER_JOIN.code());
                });
    }

    @Test
    void appendsIncrementallyWithoutDisturbingExistingRows() {
        linkPlan(null, LINK_A,
                List.of(txt("a.txt", "8613800138001\n")), CREATOR, OPERATOR);

        PullTaskStandardDraftVO view = linkPlan(
                null, LINK_A + "\n" + LINK_B,
                List.of(txt("b.txt", "8613800138002\n")), CREATOR, OPERATOR);

        // LINK_A 已成行，不参与第二轮随机；已有行的 seq 与文件都不变。
        assertThat(view.rows()).extracting(
                        PullTaskStandardExecutionRowVO::seq,
                        PullTaskStandardExecutionRowVO::normalizedLink,
                        PullTaskStandardExecutionRowVO::sourceFileName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, LINK_A, "a.txt"),
                        org.assertj.core.groups.Tuple.tuple(2, LINK_B, "b.txt"));
    }

    @Test
    void ignoresTrailingFilesWhenRemainingLinksRunOut() {
        PullTaskStandardDraftVO view = linkPlan(null, LINK_A,
                List.of(txt("a.txt", "8613800138001\n"), txt("b.txt", "8613800138002\n")),
                CREATOR, OPERATOR);

        assertThat(view.matchedCount()).isEqualTo(1);
        assertThat(view.ignoredFileCount()).isEqualTo(1);
    }

    @Test
    void rejectsZeroValidFileFromThePoolButStillReportsIt() {
        PullTaskStandardDraftVO view = linkPlan(null, LINK_A,
                List.of(txt("empty.txt", "abc\n\n")), CREATOR, OPERATOR);

        assertThat(view.matchedCount()).isZero();
        assertThat(view.fileResults()).singleElement().satisfies(file -> {
            assertThat(file.accepted()).isFalse();
            assertThat(file.rejectReason()).isNotBlank();
            assertThat(file.invalidLineCount()).isEqualTo(1);
        });
    }

    @Test
    void persistsMembersWithAdminFlagAndLineNumbers() {
        linkPlan(null, LINK_A,
                List.of(txt("a.txt", "8613800138001\n8613800138002A\n")),
                CREATOR, OPERATOR);

        long rowId = executionMapper.selectByTaskId(
                service.current(CREATOR).draftTaskId()).get(0).getId();
        assertThat(materialMapper.selectByExecution(rowId)).extracting(
                        PullTaskMaterialMember::getMemberSeq,
                        PullTaskMaterialMember::getSourceLineNo,
                        PullTaskMaterialMember::getNormalizedPhone,
                        PullTaskMaterialMember::getAdminRequired)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 1, "8613800138001", 0),
                        org.assertj.core.groups.Tuple.tuple(2, 2, "8613800138002", 1));
    }

    @Test
    void keepsEveryFormatValidLinkInThePoolForProtocolValidation() {
        PullTaskStandardDraftVO view = linkPlan(null, LINK_A + "\n" + LINK_B,
                List.of(txt("a.txt", "8613800138001\n")), CREATOR, OPERATOR);

        assertThat(view.linkLines()).extracting(PullTaskStandardLinkLineVO::status)
                .containsExactly(PullTaskStandardLinkLineStatus.VALID,
                        PullTaskStandardLinkLineStatus.VALID);
        assertThat(view.rows()).hasSize(1);
        assertThat(view.remainingLinkCount()).isEqualTo(1);
    }

    @Test
    void marksLinkOccupiedByAnotherRunningTask() {
        linkPlan(null, LINK_A,
                List.of(txt("a.txt", "8613800138001\n")), CREATOR, OPERATOR);
        executionMapper.freezeDraftRows(service.current(CREATOR).draftTaskId(), 900L);

        PullTaskStandardDraftVO view;
        try (var ignored = DataScopeContext.open(DataScope.self(602L))) {
            view = linkPlan(null, LINK_A + "\n" + LINK_C,
                    List.of(txt("c.txt", "8613800138003\n")), 602L, "运营乙");
        }

        assertThat(view.linkLines()).extracting(PullTaskStandardLinkLineVO::status)
                .containsExactly(PullTaskStandardLinkLineStatus.OCCUPIED,
                        PullTaskStandardLinkLineStatus.VALID);
        assertThat(view.rows()).singleElement()
                .satisfies(row -> assertThat(row.normalizedLink()).isEqualTo(LINK_C));
    }

    @Test
    void rejectsNonTxtUpload() {
        assertThatThrownBy(() -> linkPlan(null, LINK_A,
                List.of(new MockMultipartFile("files", "a.csv", "text/csv",
                        "8613800138001".getBytes(StandardCharsets.UTF_8))), CREATOR, OPERATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(".txt");
    }

    @Test
    void rejectsTooManyFiles() {
        List<MultipartFile> files = java.util.stream.IntStream.rangeClosed(0, 50)
                .mapToObj(index -> txt("f" + index + ".txt", "8613800138001\n"))
                .map(MultipartFile.class::cast)
                .toList();

        assertThatThrownBy(() -> linkPlan(null, LINK_A, files, CREATOR, OPERATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("50");
    }

    @Test
    void rejectsBinaryContentEvenWithTxtExtension() {
        MockMultipartFile binary = new MockMultipartFile("files", "a.txt", "text/plain",
                new byte[] {0x00, 0x01, 0x02});

        assertThatThrownBy(() -> linkPlan(
                null, LINK_A, List.of(binary), CREATOR, OPERATOR))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void planWithNoFilesOnlyReportsLinkJudgementWithoutCreatingRows() {
        PullTaskStandardDraftVO view = linkPlan(
                null, LINK_A, List.of(), CREATOR, OPERATOR);

        assertThat(view.draftTaskId()).isNotNull();
        assertThat(view.rows()).isEmpty();
        assertThat(view.linkLines()).hasSize(1);
        assertThat(view.remainingLinkCount()).isEqualTo(1);
    }

    @Test
    void plansWithLinksFromSelectedGroupFolderOnly() {
        when(groupFolderService.usableLinks(18L)).thenReturn(List.of(LINK_A));

        PullTaskStandardDraftVO view = linkPlan(
                18L, null, List.of(txt("a.txt", "8613800138001\n")), CREATOR, OPERATOR);

        assertThat(view.rows()).singleElement()
                .satisfies(row -> assertThat(row.normalizedLink()).isEqualTo(LINK_A));
    }

    @Test
    void deduplicatesFolderAndPastedLinksBeforeMatching() {
        when(groupFolderService.usableLinks(18L)).thenReturn(List.of(LINK_A));

        PullTaskStandardDraftVO view = linkPlan(
                18L, LINK_A, List.of(txt("a.txt", "8613800138001\n")), CREATOR, OPERATOR);

        assertThat(view.rows()).hasSize(1);
        assertThat(view.remainingLinkCount()).isZero();
    }

    @Test
    void missingOrCrossTenantFolderIsRejectedBeforeDraftIsWritten() {
        when(groupFolderService.usableLinks(18L)).thenThrow(
                new BusinessException(ErrorCode.NOT_FOUND, "群组分组不存在: 18"));

        assertThatThrownBy(() -> linkPlan(
                18L, LINK_A, List.of(txt("a.txt", "8613800138001\n")), CREATOR, OPERATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
        assertThat(service.current(CREATOR).draftTaskId()).isNull();
    }

    private static MockMultipartFile txt(String fileName, String content) {
        return new MockMultipartFile("files", fileName, "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
    }

    private PullTaskStandardDraftVO linkPlan(
            Long groupFolderId,
            String linksText,
            List<? extends MultipartFile> files,
            long userId,
            String operatorName) {
        return service.plan(PullTaskCreationMode.PASTED_LINK, groupFolderId, linksText,
                List.copyOf(files), userId, operatorName);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_draft_plan_test");
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
        GroupFolderService groupFolderService() {
            return mock(GroupFolderService.class);
        }

        @Bean
        PullTaskLinkProbeService probeService() {
            return new PullTaskLinkProbeService();
        }

        @Bean
        PullTaskMaterialTxtParser txtParser() {
            return new PullTaskMaterialTxtParser();
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
                                                  PullTaskStandardDraftWriter writer,
                                                  PullTaskMaterialTxtParser txtParser,
                                                  PullTaskLinkProbeService probeService,
                                                  GroupFolderService groupFolderService) {
            return new PullTaskStandardDraftServiceImpl(
                    pullTaskMapper, executionMapper, writer, txtParser,
                    probeService, groupFolderService);
        }
    }
}
