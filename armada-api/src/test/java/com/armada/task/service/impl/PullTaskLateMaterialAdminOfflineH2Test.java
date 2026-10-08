package com.armada.task.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.armada.boot.config.MyBatisConfig;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.model.dto.PullTaskMaterialAdminCallback;
import com.armada.task.model.enums.PullTaskMaterialAdminProtocolOutcome;
import com.armada.task.scheduler.PullTaskOfflineRoleWaitProperties;
import com.armada.task.scheduler.PullTaskOperationDelayPolicy;
import com.armada.task.scheduler.PullTaskUnknownResultResources;
import com.armada.task.service.PullTaskProtocolResultCallbackService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** 已离开提权阶段的离线拒绝只恢复料子待处理事实，不能回退执行行。 */
class PullTaskLateMaterialAdminOfflineH2Test {
    private JdbcTemplate jdbc;
    private PullTaskOfflineRoleWaitProperties properties;
    private PullTaskProtocolResultCallbackService service;

    @BeforeEach void setUp() throws Exception {
        TenantContext.set(7L);
        var source = PullTaskNormalLinkH2Support.dataSource("late_admin_offline_" + UUID.randomUUID());
        PullTaskNormalLinkH2Support.resetSchema(source);
        jdbc = new JdbcTemplate(source);
        var config = new MyBatisConfig();
        var sql = new SqlSessionTemplate(PullTaskNormalLinkH2Support.sqlSessionFactory(source,
                config.mybatisPlusInterceptor(config.tenantLineHandler()),
                "mapper/task/PullTaskAccountActionMapper.xml", "mapper/task/PullTaskPullCallMapper.xml",
                "mapper/task/PullTaskMaterialMemberMapper.xml", "mapper/task/PullTaskGroupAccountMapper.xml",
                "mapper/task/PullTaskGroupExecutionMapper.xml"));
        properties = new PullTaskOfflineRoleWaitProperties();
        var target = new PullTaskProtocolResultCallbackServiceImpl(new PullTaskUnknownResultResources(
                sql.getMapper(PullTaskAccountActionMapper.class), sql.getMapper(PullTaskPullCallMapper.class),
                sql.getMapper(PullTaskPullCallMemberAttemptMapper.class), sql.getMapper(PullTaskMaterialMemberMapper.class),
                sql.getMapper(PullTaskGroupAccountMapper.class)), sql.getMapper(PullTaskGroupExecutionMapper.class),
                mock(PullTaskPullCallParticipantResultService.class), new PullTaskOperationDelayPolicy(), properties);
        var proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        service = (PullTaskProtocolResultCallbackService) proxy.getProxy();
        jdbc.update("INSERT INTO pull_task_group_execution(id,tenant_id,task_id,seq,source_file_index,source_file_name,execution_status,stage,manual_paused,"
                + "next_run_at,lock_owner,lock_expires_at,version,created_at,updated_at) VALUES(11,7,1,1,1,'members.txt',2,8,0,8000,'worker',9000,6,100,100)");
        jdbc.update("INSERT INTO pull_task_group_account(id,tenant_id,task_id,group_execution_id,account_id,account_phone,role_type,"
                + "role_seq,admin_status,created_at,updated_at) VALUES(21,7,1,11,901,'8613800000901',1,1,2,100,100)");
        jdbc.update("INSERT INTO pull_task_material_member(id,tenant_id,group_execution_id,member_seq,source_line_no,normalized_phone,"
                + "admin_required,pull_status,pull_failure_count,wa_jid,admin_status,admin_command_id,created_at,updated_at) "
                + "VALUES(31,7,11,1,1,'8613900000001',1,2,0,'8613900000001@s.whatsapp.net',5,'admin-1',100,100)");
    }

    @AfterEach void clearTenant() { TenantContext.clear(); }

    @Test void lateOfflineRejectionReturnsMaterialToPendingWithoutRewindingStage() {
        assertThat(service.handleMaterialAdmin(callback())).isTrue();
        assertThat(value("admin_status", "pull_task_material_member")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT admin_command_id FROM pull_task_material_member",String.class)).isNull();
        assertThat(value("pull_failure_count", "pull_task_material_member")).isZero();
        assertExecutionUnchanged(2);
    }

    @Test void terminalExecutionIsNeverReopenedByLateOfflineRejection() {
        jdbc.update("UPDATE pull_task_group_execution SET execution_status=5 WHERE id=11");
        assertThat(service.handleMaterialAdmin(callback())).isTrue();
        assertThat(value("admin_status", "pull_task_material_member")).isEqualTo(1);
        assertExecutionUnchanged(5);
    }

    @Test void disabledSwitchPreservesLateFailedFact() {
        properties.setEnabled(false);
        assertThat(service.handleMaterialAdmin(callback())).isTrue();
        assertThat(value("admin_status", "pull_task_material_member")).isEqualTo(4);
        assertExecutionUnchanged(2);
    }

    @Test void callbackRestoresTenantAndDoesNotTouchAnotherTenant() {
        jdbc.update("INSERT INTO pull_task_group_execution(id,tenant_id,task_id,seq,source_file_index,source_file_name,execution_status,stage,manual_paused,"
                + "next_run_at,version,created_at,updated_at) VALUES(12,8,1,1,1,'other.txt',2,7,0,7000,3,100,100)");
        TenantContext.set(8L);
        assertThat(service.handleMaterialAdmin(callback())).isTrue();
        assertThat(TenantContext.get()).isEqualTo(8L);
        assertThat(jdbc.queryForObject("SELECT version FROM pull_task_group_execution WHERE id=12",Integer.class)).isEqualTo(3);
        assertThat(value("admin_status", "pull_task_material_member")).isEqualTo(1);
    }

    private static PullTaskMaterialAdminCallback callback() {
        return new PullTaskMaterialAdminCallback(7,1,11,31,901,"manager-901","admin-1",1,
                "8613900000001@s.whatsapp.net",PullTaskMaterialAdminProtocolOutcome.FAILED,
                "ACCOUNT_NOT_ONLINE","offline",false,5000);
    }

    private int value(String column,String table) { return jdbc.queryForObject("SELECT " + column + " FROM " + table,Integer.class); }
    private void assertExecutionUnchanged(int status) {
        assertThat(jdbc.queryForObject("SELECT execution_status FROM pull_task_group_execution WHERE id=11",Integer.class)).isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT stage FROM pull_task_group_execution WHERE id=11",Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT version FROM pull_task_group_execution WHERE id=11",Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT next_run_at FROM pull_task_group_execution WHERE id=11",Long.class)).isEqualTo(8000L);
    }
}
