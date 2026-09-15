package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.resource.mapper.GroupDataPackageMapper;
import com.armada.resource.mapper.GroupDataPackagePhoneMapper;
import com.armada.resource.mapper.GroupDataPackageStatMapper;
import com.armada.resource.service.GroupDataPackageAllocationService;
import com.armada.resource.service.impl.GroupDataPackageAllocationServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.GroupDataPackageTaskProjectionMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.service.impl.GroupDataPackageTaskProjectionServiceImpl;
import com.armada.task.service.impl.PullTaskDataPackageSourceService;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.nio.charset.StandardCharsets;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** 任务来源与四表资源使用同一真实事务，验证完整回写而非模拟跨域服务。 */
@SpringJUnitConfig(GroupDataPackageTaskResourceH2Test.Config.class)
@TestExecutionListeners(listeners = DependencyInjectionTestExecutionListener.class, inheritListeners = false)
class GroupDataPackageTaskResourceH2Test {
    @Autowired private DataSource dataSource;
    @Autowired private DataSourceTransactionManager transactions;
    @Autowired private PullTaskDataPackageSourceService source;
    @Autowired private GroupDataPackageTaskProjectionService projection;
    @Autowired private GroupDataPackageAllocationService allocations;
    @Autowired private com.armada.task.scheduler.PullTaskParentCompletionService completion;
    private JdbcTemplate jdbc;

    @BeforeEach
    void reset() throws Exception {
        TenantContext.set(7L);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        String schema;
        try (var input = new ClassPathResource("db/migration/V191__group_data_package.sql").getInputStream()) {
            schema = new String(input.readAllBytes(), StandardCharsets.UTF_8).split("-- 页面")[0];
        }
        for (String statement : schema.split(";")) {
            if (!statement.isBlank()) { jdbc.execute(statement); }
        }
        jdbc.update("INSERT INTO group_data_package(id,tenant_id,name,created_by,created_at,updated_at) VALUES(81,7,'包',9,1,1)");
        jdbc.update("INSERT INTO group_data_package_stat(package_id,tenant_id,generation,total_count,unused_count) VALUES(81,7,1,3,3)");
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO group_data_package_phone(id,tenant_id,package_id,generation,source_import_id,phone,"
                    + "member_seq,source_line_no,created_at,updated_at) VALUES(?,7,81,1,1,?,?,?,1,1)",
                    90L + i, "63917000000" + i, i, i);
        }
        task(11L);
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void claimResultsEndAndReclaimKeepBothDomainsConsistent() {
        var execution = execution(101, 11, 1, List.of(91L, 92L, 93L));
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> source.claim(List.of(execution)));
        assertThat(statuses()).containsExactly(2, 2, 2);
        assertThat(jdbc.queryForList("SELECT source_allocation_version FROM pull_task_material_member ORDER BY id", Long.class))
                .containsExactly(1L, 1L, 1L);
        jdbc.update("UPDATE pull_task SET status='EXECUTING' WHERE id=11");
        jdbc.update("UPDATE pull_task_group_execution SET execution_status=2 WHERE id=101");
        jdbc.update("UPDATE pull_task_material_member SET pull_status=2 WHERE source_package_phone_id=91");
        jdbc.update("UPDATE pull_task_material_member SET pull_status=4 WHERE source_package_phone_id=92");
        projection.synchronizeTask(11L);
        assertThat(statuses()).containsExactly(3, 7, 2);

        jdbc.update("UPDATE pull_task SET status='ENDED' WHERE id=11");
        jdbc.update("UPDATE pull_task_group_execution SET execution_status=6 WHERE id=101");
        jdbc.update("UPDATE pull_task_material_member SET pull_status=5 WHERE source_package_phone_id=93");
        projection.synchronizeTask(11L);
        assertThat(statuses()).containsExactly(3, 7, 1);
        assertCounts(1, 0, 1, 1);

        task(12L);
        var next = execution(202, 12, 1, List.of(93L));
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> source.claim(List.of(next)));
        projection.synchronizeTask(11L); // 旧任务的释放不能解除新任务的占用。
        assertThat(statuses()).containsExactly(3, 7, 2);
        jdbc.update("UPDATE pull_task_material_member SET pull_status=2 WHERE group_execution_id=202");
        projection.synchronizeTask(12L);
        projection.synchronizeTask(12L); // 幂等重复投影不能重复计数。
        assertThat(statuses()).containsExactly(3, 7, 3);
        assertCounts(0, 0, 2, 1);
        TenantContext.set(8L);
        projection.synchronizeTask(12L);
        assertThat(statuses()).containsExactly(3, 7, 3);
    }

    @Test
    void conflictInSecondExecutionRollsBackFirstClaimAndSourceBinding() {
        var first = execution(101, 11, 1, List.of(91L));
        var second = execution(102, 11, 2, List.of(92L));
        allocations.claim(new GroupDataPackageAllocationService.ClaimRequest(81, 1, 999, 1, List.of(92L)));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(
                ignored -> source.claim(List.of(first, second)))).isInstanceOf(BusinessException.class);
        assertThat(statuses()).containsExactly(1, 2, 1);
        assertThat(jdbc.queryForObject("SELECT source_allocation_version FROM pull_task_material_member WHERE group_execution_id=101", Long.class))
                .isNull();
        assertCounts(2, 1, 0, 0);
    }

    @Test
    void oneFailedExecutionReleasesUnusedPhonesWhileSiblingKeepsRunning() {
        var failed = execution(101, 11, 1, List.of(91L));
        var running = execution(102, 11, 2, List.of(92L));
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> source.claim(List.of(failed, running)));
        jdbc.update("UPDATE pull_task SET status='EXECUTING' WHERE id=11");
        jdbc.update("UPDATE pull_task_group_execution SET execution_status=5 WHERE id=101");
        jdbc.update("UPDATE pull_task_group_execution SET execution_status=2 WHERE id=102");

        new TransactionTemplate(transactions).executeWithoutResult(
                ignored -> completion.completeIfTerminalByExecutionId(101L, 100L));

        assertThat(statuses()).containsExactly(1, 2, 1);
        assertCounts(2, 1, 0, 0);
        assertThat(jdbc.queryForObject("SELECT status FROM pull_task WHERE id=11", String.class)).isEqualTo("EXECUTING");
    }

    private void task(long id) {
        jdbc.update("INSERT INTO pull_task(id,tenant_id,task_name,mode,status,config_json,created_at,updated_at) VALUES(?,7,'task','NORMAL_LINK','WAIT_START','{}',1,1)", id);
    }

    private PullTaskGroupExecution execution(long id, long taskId, int seq, List<Long> phones) {
        jdbc.update("INSERT INTO pull_task_group_execution(id,tenant_id,task_id,seq,source_file_index,attempt_no,source_file_name,"
                + "source_package_id,source_package_generation,execution_status,created_at,updated_at) VALUES(?,7,?,?,?,1,'包.txt',81,1,1,1,1)",
                id, taskId, seq, seq);
        for (int i = 0; i < phones.size(); i++) {
            jdbc.update("INSERT INTO pull_task_material_member(tenant_id,group_execution_id,member_seq,source_line_no,normalized_phone,"
                    + "source_package_phone_id,pull_status,created_at,updated_at) VALUES(7,?,?,?,?,?,0,1,1)",
                    id, i + 1, i + 1, "63917000000" + (phones.get(i) - 90), phones.get(i));
        }
        var row = new PullTaskGroupExecution();
        row.setId(id); row.setTaskId(taskId); row.setSeq(seq);
        row.setSourcePackageId(81L); row.setSourcePackageGeneration(1);
        return row;
    }

    private List<Integer> statuses() {
        return jdbc.queryForList("SELECT status FROM group_data_package_phone ORDER BY id", Integer.class);
    }

    private void assertCounts(int unused, int claimed, int success, int unknown) {
        var stat = jdbc.queryForMap("SELECT * FROM group_data_package_stat WHERE package_id=81");
        assertThat(stat.get("unused_count")).isEqualTo(unused);
        assertThat(stat.get("claimed_count")).isEqualTo(claimed);
        assertThat(stat.get("success_count")).isEqualTo(success);
        assertThat(stat.get("unknown_count")).isEqualTo(unknown);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({MyBatisConfig.class, GroupDataPackageAllocationServiceImpl.class,
            GroupDataPackageTaskProjectionServiceImpl.class, PullTaskDataPackageSourceService.class,
            com.armada.task.scheduler.PullTaskParentCompletionService.class})
    static class Config {
        @Bean DataSource dataSource() { return PullTaskNormalLinkH2Support.dataSource("group_data_package_resource_task"); }
        @Bean DataSourceTransactionManager transactions(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean SqlSessionFactory factory(DataSource ds, MybatisPlusInterceptor interceptor) throws Exception {
            return PullTaskNormalLinkH2Support.sqlSessionFactory(ds, interceptor,
                    "mapper/task/GroupDataPackageTaskProjectionMapper.xml", "mapper/task/PullTaskMaterialMemberMapper.xml",
                    "mapper/task/PullTaskMapper.xml", "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/resource/GroupDataPackageMapper.xml", "mapper/resource/GroupDataPackagePhoneMapper.xml",
                    "mapper/resource/GroupDataPackageStatMapper.xml");
        }
        @Bean SqlSessionTemplate template(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean GroupDataPackageTaskProjectionMapper projectionMapper(SqlSessionTemplate t) { return t.getMapper(GroupDataPackageTaskProjectionMapper.class); }
        @Bean PullTaskMaterialMemberMapper materialMapper(SqlSessionTemplate t) { return t.getMapper(PullTaskMaterialMemberMapper.class); }
        @Bean GroupDataPackageMapper packages(SqlSessionTemplate t) { return t.getMapper(GroupDataPackageMapper.class); }
        @Bean GroupDataPackagePhoneMapper phones(SqlSessionTemplate t) { return t.getMapper(GroupDataPackagePhoneMapper.class); }
        @Bean com.armada.task.mapper.PullTaskMapper tasks(SqlSessionTemplate t) { return t.getMapper(com.armada.task.mapper.PullTaskMapper.class); }
        @Bean com.armada.task.mapper.PullTaskGroupExecutionMapper executions(SqlSessionTemplate t) { return t.getMapper(com.armada.task.mapper.PullTaskGroupExecutionMapper.class); }
        @Bean GroupDataPackageStatMapper stats(SqlSessionTemplate t) { return t.getMapper(GroupDataPackageStatMapper.class); }
    }
}
