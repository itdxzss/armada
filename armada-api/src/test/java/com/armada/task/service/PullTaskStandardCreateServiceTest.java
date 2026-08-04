package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.account.model.entity.AccountGroup;
import com.armada.account.service.AccountGroupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.group.service.GroupInvitePageFetcher;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.dto.PullTaskStandardCreateDTO;
import com.armada.task.model.entity.PullTask;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import com.armada.task.model.vo.PullTaskStandardCreatedVO;
import com.armada.task.scheduler.PullTaskExecutionDispatchTrigger;
import com.armada.task.service.impl.PullTaskStandardCreateServiceImpl;
import com.armada.task.service.impl.PullTaskStandardDraftServiceImpl;
import com.armada.task.service.impl.PullTaskStandardDraftWriter;
import com.armada.task.service.impl.PullTaskStandardDraftWriter.AppendRow;
import com.armada.task.service.impl.PullTaskStandardSettingWriter;
import com.armada.task.service.impl.PullTaskStandardStartServiceImpl;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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

/** 普通群链接任务提交冻结的 H2 集成测试。 */
@SpringJUnitConfig(PullTaskStandardCreateServiceTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskStandardCreateServiceTest {

    private static final long CREATOR = 501L;
    private static final long OTHER_CREATOR = 602L;
    private static final String OPERATOR = "运营甲";
    private static final String LINK_A = "chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA";
    private static final String LINK_B = "chat.whatsapp.com/BBBBBBBBBBBBBBBBBBBBBB";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskStandardCreateService service;

    @Autowired
    private PullTaskStandardDraftWriter writer;

    @Autowired
    private PullTaskMapper pullTaskMapper;

    @Autowired
    private PullTaskGroupExecutionMapper executionMapper;

    @Autowired
    private PullTaskStandardSettingMapper settingMapper;

    @Autowired
    private AccountGroupService accountGroupService;

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
    void submitFreezesRowsWritesSettingAndFlipsTaskToWaitStart() {
        long taskId = seedDraftWithTwoRows(CREATOR);

        service.create(validRequest(taskId, 1), CREATOR);

        PullTask task = pullTaskMapper.selectLifecycle(taskId);
        assertThat(task.getStatus()).isEqualTo("WAIT_START");
        assertThat(task.getVersion()).isEqualTo(2);
        assertThat(task.getGroupCount()).isEqualTo(2);
        // expected_pull_count 是全部执行行 valid_member_count 之和。
        assertThat(task.getExpectedPullCount()).isEqualTo(2);
        assertThat(executionMapper.selectByTaskId(taskId))
                .allSatisfy(row -> {
                    assertThat(row.getExecutionStatus()).isEqualTo(1);
                    assertThat(row.getGroupLinkId()).isNotNull();
                });
        assertThat(settingMapper.selectByTaskId(taskId).getRequiredManagerCount()).isZero();
    }

    @Test
    void autoStartUsesTheSharedStartServiceAfterFreezingTheTask() {
        long taskId = seedDraftWithTwoRows(CREATOR);

        PullTaskStandardCreatedVO created = service.create(
                withAutoStart(validRequest(taskId, 1), 1), CREATOR);

        assertThat(created.status()).isEqualTo("EXECUTING");
        assertThat(pullTaskMapper.selectLifecycle(taskId).getStatus()).isEqualTo("EXECUTING");
    }

    @Test
    void submitRollsBackEntirelyWhenAnyLinkIsAlreadyOccupied() {
        long occupiedTaskId = seedDraftWithTwoRows(CREATOR);
        executionMapper.freezeDraftRows(occupiedTaskId, 800L);
        long taskId = seedDraftWithTwoRows(OTHER_CREATOR);

        assertThatThrownBy(() -> service.create(validRequest(taskId, 1), OTHER_CREATOR))
                .isInstanceOf(BusinessException.class);

        // 整单回滚：草稿完整保留，可继续编辑。
        PullTask task = pullTaskMapper.selectLifecycle(taskId);
        assertThat(task.getStatus()).isEqualTo("DRAFT");
        assertThat(executionMapper.selectByTaskId(taskId))
                .allSatisfy(row -> assertThat(row.getExecutionStatus()).isZero());
        assertThat(settingMapper.selectByTaskId(taskId)).isNull();
    }

    @Test
    void repeatedSubmissionReturnsTheSameTaskWithoutCreatingASecondOne() {
        long taskId = seedDraftWithTwoRows(CREATOR);
        service.create(validRequest(taskId, 1), CREATOR);

        PullTaskStandardCreatedVO second = service.create(validRequest(taskId, 1), CREATOR);

        assertThat(second.id()).isEqualTo(taskId);
        assertThat(pullTaskMapper.selectLifecycle(taskId).getVersion()).isEqualTo(2);
    }

    @Test
    void submitIsRejectedWhenDraftHasNoExecutionRow() {
        long taskId = writer.ensureDraft(CREATOR, OPERATOR, 100L).getId();

        assertThatThrownBy(() -> service.create(validRequest(taskId, 1), CREATOR))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("群链接");
    }

    @Test
    void submitIsRejectedForAnotherUsersDraft() {
        long taskId = seedDraftWithTwoRows(CREATOR);

        assertThatThrownBy(() -> service.create(validRequest(taskId, 1), OTHER_CREATOR))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsPullCountRangeWithMinGreaterThanMax() {
        long taskId = seedDraftWithTwoRows(CREATOR);

        assertThatThrownBy(() -> service.create(
                withPullCount(validRequest(taskId, 1), 9, 3), CREATOR))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsGroupThatDoesNotBelongToTenant() {
        long taskId = seedDraftWithTwoRows(CREATOR);
        when(accountGroupService.requireExisting(999L))
                .thenThrow(new BusinessException(ErrorCode.NOT_FOUND, "账号分组不存在"));

        assertThatThrownBy(() -> service.create(
                withManagerGroup(validRequest(taskId, 1), 999L), CREATOR))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 造一个带两条执行行的草稿，链接与料子固定为 LINK_A/LINK_B 各一个有效号码。
     *
     * @param creator 创建人用户 ID
     * @return 草稿任务 ID
     */
    private long seedDraftWithTwoRows(long creator) {
        long taskId = writer.ensureDraft(creator, OPERATOR, 100L).getId();
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

    /**
     * 填满合法值的提交入参。
     *
     * @param taskId  草稿任务 ID
     * @param version 草稿任务乐观锁版本
     * @return 合法入参
     */
    private static PullTaskStandardCreateDTO validRequest(long taskId, int version) {
        return new PullTaskStandardCreateDTO(taskId, version, "任务", null, 0, 1, 3, 8, 30, 2, 2, 1, 0,
                11L, 12L, 13L);
    }

    /**
     * 派生入参：替换拉人料子人数区间。
     *
     * @param base 基础入参
     * @param min  下限
     * @param max  上限
     * @return 替换区间后的入参
     */
    private static PullTaskStandardCreateDTO withPullCount(PullTaskStandardCreateDTO base,
                                                           int min, int max) {
        return new PullTaskStandardCreateDTO(base.draftTaskId(), base.version(), base.taskName(),
                base.remark(), base.autoStart(), base.materialAdminTiming(), min, max,
                base.pullIntervalSeconds(), base.pullerCountPerGroup(), base.stationCountPerCall(),
                base.concurrentGroupCount(), base.pullerRiskMinutes(), base.managerGroupId(),
                base.pullerGroupId(), base.stationGroupId());
    }

    /**
     * 派生入参：替换管理账号分组 ID。
     *
     * @param base           基础入参
     * @param managerGroupId 替换后的管理账号分组 ID
     * @return 替换分组后的入参
     */
    private static PullTaskStandardCreateDTO withManagerGroup(PullTaskStandardCreateDTO base,
                                                              long managerGroupId) {
        return new PullTaskStandardCreateDTO(base.draftTaskId(), base.version(), base.taskName(),
                base.remark(), base.autoStart(), base.materialAdminTiming(), base.pullCountMin(),
                base.pullCountMax(), base.pullIntervalSeconds(), base.pullerCountPerGroup(),
                base.stationCountPerCall(), base.concurrentGroupCount(), base.pullerRiskMinutes(),
                managerGroupId, base.pullerGroupId(), base.stationGroupId());
    }

    private static PullTaskStandardCreateDTO withAutoStart(PullTaskStandardCreateDTO base,
                                                            int autoStart) {
        return new PullTaskStandardCreateDTO(base.draftTaskId(), base.version(), base.taskName(),
                base.remark(), autoStart, base.materialAdminTiming(), base.pullCountMin(),
                base.pullCountMax(), base.pullIntervalSeconds(), base.pullerCountPerGroup(),
                base.stationCountPerCall(), base.concurrentGroupCount(), base.pullerRiskMinutes(),
                base.managerGroupId(), base.pullerGroupId(), base.stationGroupId());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_create_test");
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
                    "mapper/task/PullTaskMaterialMemberMapper.xml",
                    "mapper/task/PullTaskStandardSettingMapper.xml");
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
        PullTaskStandardSettingMapper settingMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskStandardSettingMapper.class);
        }

        @Bean
        GroupInvitePageFetcher invitePageFetcher() {
            return mock(GroupInvitePageFetcher.class);
        }

        @Bean
        PullTaskLinkProbeService probeService(GroupInvitePageFetcher fetcher) {
            return new PullTaskLinkProbeService(fetcher, Runnable::run);
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
                                                  PullTaskLinkProbeService probeService) {
            return new PullTaskStandardDraftServiceImpl(
                    pullTaskMapper, executionMapper, writer, txtParser, probeService);
        }

        @Bean
        AccountGroupService accountGroupService() {
            AccountGroupService mock = mock(AccountGroupService.class);
            AccountGroup group = new AccountGroup();
            group.setName("默认分组");
            when(mock.requireExisting(anyLong())).thenReturn(group);
            return mock;
        }

        @Bean
        GroupLinkRegistryService groupLinkRegistryService() {
            GroupLinkRegistryService mock = mock(GroupLinkRegistryService.class);
            AtomicLong sequence = new AtomicLong(1000);
            when(mock.registerPullTaskTargets(anyList(), anyLong())).thenAnswer(invocation -> {
                List<String> links = invocation.getArgument(0);
                Map<String, Long> result = new LinkedHashMap<>();
                for (String link : links) {
                    result.put(link, sequence.incrementAndGet());
                }
                return result;
            });
            return mock;
        }

        @Bean
        PullTaskStandardSettingWriter settingWriter(PullTaskStandardSettingMapper settingMapper,
                                                     AccountGroupService accountGroupService) {
            return new PullTaskStandardSettingWriter(settingMapper, accountGroupService);
        }

        @Bean
        PullTaskExecutionDispatchTrigger dispatchTrigger() {
            return mock(PullTaskExecutionDispatchTrigger.class);
        }

        @Bean
        PullTaskStandardStartService startService(PullTaskMapper pullTaskMapper,
                                                  PullTaskStandardSettingMapper settingMapper,
                                                  PullTaskExecutionDispatchTrigger trigger) {
            return new PullTaskStandardStartServiceImpl(
                    pullTaskMapper, settingMapper, trigger, () -> 900L);
        }

        @Bean
        PullTaskStandardCreateService createService(PullTaskMapper pullTaskMapper,
                                                     PullTaskGroupExecutionMapper executionMapper,
                                                     PullTaskStandardSettingWriter settingWriter,
                                                     GroupLinkRegistryService groupLinkRegistryService,
                                                     PullTaskStandardStartService startService) {
            return new PullTaskStandardCreateServiceImpl(pullTaskMapper, executionMapper,
                    settingWriter, groupLinkRegistryService, startService);
        }
    }
}
