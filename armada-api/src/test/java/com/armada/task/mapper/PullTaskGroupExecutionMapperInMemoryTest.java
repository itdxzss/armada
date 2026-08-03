package com.armada.task.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskExecutionStatus;
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

/** 群链接执行行 Mapper 的 H2 MySQL 模式测试：链接占用、跨租户调度扫描与调度锁。 */
@SpringJUnitConfig(PullTaskGroupExecutionMapperInMemoryTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskGroupExecutionMapperInMemoryTest {

    private static final String LINK = "chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PullTaskGroupExecutionMapper mapper;

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
    void insertDraftFillsGeneratedId() {
        PullTaskGroupExecution row = draft(100L, 1, LINK, 1);
        mapper.insertDraft(row);

        assertThat(row.getId()).isNotNull();
        assertThat(mapper.selectByTaskId(100L)).hasSize(1);
    }

    @Test
    void twoDraftsMayHoldTheSameLinkBecauseDraftsDoNotOccupy() {
        mapper.insertDraft(draft(100L, 1, LINK, 1));
        mapper.insertDraft(draft(200L, 1, LINK, 1));

        // 草稿 execution_status=0，link_occupancy_key 为 NULL，不参与唯一约束。
        assertThat(mapper.selectByTaskId(100L)).hasSize(1);
        assertThat(mapper.selectByTaskId(200L)).hasSize(1);
    }

    @Test
    void freezingTheSecondTaskOnTheSameLinkIsRejected() {
        mapper.insertDraft(draft(100L, 1, LINK, 1));
        mapper.insertDraft(draft(200L, 1, LINK, 1));

        assertThat(mapper.freezeDraftRows(100L, 500L)).isEqualTo(1);

        // 第一个任务已占用该链接，第二个任务冻结时唯一键冲突。
        assertThatThrownBy(() -> mapper.freezeDraftRows(200L, 600L))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void inviteCodesDifferingOnlyByCaseCoexistWithinOneTask() {
        // H2 默认大小写敏感，这里只验证唯一键维度正确；
        // MySQL 侧的 ai_ci 风险由 PullTaskNormalLinkMigrationSqlTest 的
        // ascii_bin 断言兜住。
        mapper.insertDraft(draft(100L, 1, "chat.whatsapp.com/AAAA", 1));
        mapper.insertDraft(draft(100L, 2, "chat.whatsapp.com/aaaa", 2));

        assertThat(mapper.selectByTaskId(100L)).hasSize(2);
    }

    @Test
    void duplicateLinkWithinOneTaskIsRejected() {
        mapper.insertDraft(draft(100L, 1, LINK, 1));

        assertThatThrownBy(() -> mapper.insertDraft(draft(100L, 2, LINK, 2)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void deleteDraftRemovesOnlyDraftRowsOfThatTask() {
        mapper.insertDraft(draft(100L, 1, LINK, 1));
        mapper.insertDraft(draft(100L, 2, "chat.whatsapp.com/BBBB", 2));
        mapper.freezeDraftRows(100L, 500L);
        mapper.insertDraft(draft(100L, 3, "chat.whatsapp.com/CCCC", 3));

        // 只清未冻结的草稿行，已冻结的执行行不受影响。
        assertThat(mapper.deleteDraftByTaskId(100L)).isEqualTo(1);
        assertThat(mapper.selectByTaskId(100L)).hasSize(2);
    }

    @Test
    void claimDueScansAcrossTenantsWithoutTenantContext() {
        mapper.insertDraft(draft(100L, 1, LINK, 1));
        mapper.freezeDraftRows(100L, 500L);

        TenantContext.set(8L);
        mapper.insertDraft(draft(300L, 1, "chat.whatsapp.com/DDDD", 1));
        mapper.freezeDraftRows(300L, 500L);

        // 调度器没有租户上下文；@InterceptorIgnore 让它能看到全部租户的待执行行。
        TenantContext.clear();
        assertThat(mapper.claimDue(10, 600L, "worker-1", 660L)).isEqualTo(2);
        assertThat(mapper.selectClaimed("worker-1"))
                .extracting(PullTaskGroupExecution::getTaskId)
                .containsExactlyInAnyOrder(100L, 300L);
    }

    @Test
    void claimDueSkipsManuallyPausedAndFutureRows() throws SQLException {
        mapper.insertDraft(draft(100L, 1, LINK, 1));
        mapper.insertDraft(draft(100L, 2, "chat.whatsapp.com/BBBB", 2));
        mapper.freezeDraftRows(100L, 500L);
        executeRaw("UPDATE pull_task_group_execution SET manual_paused = 1 WHERE seq = 1");
        executeRaw("UPDATE pull_task_group_execution SET next_run_at = 9999 WHERE seq = 2");

        TenantContext.clear();
        // 人工暂停优先于资源自动恢复；未到调度时间的行也不取。
        assertThat(mapper.claimDue(10, 600L, "worker-1", 660L)).isZero();
    }

    @Test
    void expiredLockCanBeReclaimedByAnotherWorker() {
        mapper.insertDraft(draft(100L, 1, LINK, 1));
        mapper.freezeDraftRows(100L, 500L);

        TenantContext.clear();
        assertThat(mapper.claimDue(10, 600L, "worker-1", 660L)).isEqualTo(1);
        // 锁未过期时别的实例抢不到。
        assertThat(mapper.claimDue(10, 610L, "worker-2", 670L)).isZero();
        // 锁过期后可被回收，避免实例崩溃导致执行行永久卡死。
        assertThat(mapper.claimDue(10, 700L, "worker-2", 760L)).isEqualTo(1);
        assertThat(mapper.selectClaimed("worker-1")).isEmpty();
    }

    @Test
    void updateCheckpointRespectsOptimisticLock() {
        PullTaskGroupExecution row = draft(100L, 1, LINK, 1);
        mapper.insertDraft(row);

        assertThat(mapper.updateCheckpoint(row.getId(), 1, 2, 3, 4, 800L, 800L)).isEqualTo(1);
        // 拿旧版本号再提交必须被挡掉。
        assertThat(mapper.updateCheckpoint(row.getId(), 1, 5, 6, 5, 900L, 900L)).isZero();

        PullTaskGroupExecution saved = mapper.selectByTaskId(100L).get(0);
        assertThat(saved.getNextManagerIndex()).isEqualTo(2);
        assertThat(saved.getNextPullerIndex()).isEqualTo(3);
        assertThat(saved.getStage()).isEqualTo(4);
        assertThat(saved.getVersion()).isEqualTo(2);
    }

    @Test
    void otherTenantExecutionRowsAreInvisible() {
        mapper.insertDraft(draft(100L, 1, LINK, 1));

        TenantContext.set(8L);
        assertThat(mapper.selectByTaskId(100L)).isEmpty();
        assertThat(mapper.deleteDraftByTaskId(100L)).isZero();
    }

    private PullTaskGroupExecution draft(long taskId, int seq, String link, int fileIndex) {
        PullTaskGroupExecution row = new PullTaskGroupExecution();
        row.setTaskId(taskId);
        row.setSeq(seq);
        row.setNormalizedLink(link);
        row.setInviteCode(link.substring(link.lastIndexOf('/') + 1));
        row.setSourceLinkLineNo(seq);
        row.setSourceFileIndex(fileIndex);
        row.setSourceFileName("material-" + fileIndex + ".txt");
        row.setTotalLineCount(10);
        row.setValidMemberCount(8);
        row.setInvalidLineCount(1);
        row.setDuplicateLineCount(1);
        row.setExecutionStatus(PullTaskExecutionStatus.DRAFT.code());
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        return row;
    }

    private void executeRaw(String sql) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MyBatisConfig.class)
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_group_execution_test");
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource,
                                            MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(
                    dataSource, interceptor, "mapper/task/PullTaskGroupExecutionMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        PullTaskGroupExecutionMapper pullTaskGroupExecutionMapper(
                SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(PullTaskGroupExecutionMapper.class);
        }
    }
}
