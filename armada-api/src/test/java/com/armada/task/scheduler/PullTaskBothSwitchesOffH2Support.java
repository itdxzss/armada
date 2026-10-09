package com.armada.task.scheduler;

import static org.mockito.Mockito.mock;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.account.service.AccountStateChangedEvent;
import com.armada.account.service.impl.AccountProtocolLookupServiceImpl;
import com.armada.account.service.impl.AccountStateEventServiceImpl;
import com.armada.account.state.PullTaskPullerAccountStateChangedSideEffect;
import com.armada.account.takeover.AccountTakeoverH2Support;
import com.armada.boot.config.MyBatisConfig;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.platform.protocol.port.FixedAccountGroupMetadataPort;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.platform.protocol.port.GroupInvitePort;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskNormalLinkSchema;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.enums.PullTaskGroupAccountAvailability;
import com.armada.task.service.PullTaskGroupExecutionFailureService;
import com.armada.task.service.PullTaskPullerAccountStateService;
import com.armada.task.service.impl.PullTaskGroupProfileDispatcher;
import com.armada.task.service.impl.PullTaskPullerAccountStateServiceImpl;
import com.armada.task.service.impl.PullTaskPullerAccountStateResources;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** 双开关关闭回归的共享同库装配；账号与任务实际使用同一组关闭配置及真实 Mapper。 */
public final class PullTaskBothSwitchesOffH2Support {
    public static final long NOW = 10_000_000L;
    public static final String OWNER = "both-off-worker";
    public final AccountTakeoverH2Support accountSupport;
    public final PullTaskOfflineRoleWaitProperties properties = new PullTaskOfflineRoleWaitProperties();
    public final JdbcTemplate jdbc;
    public final AccountProtocolLookupService lookup;
    public final PullTaskGroupExecutionMapper executions;
    public final PullTaskGroupAccountMapper roles;
    public final PullTaskGroupCreateTransactionService creates;
    public final PullTaskResourceRecoveryTransactionService recovery;
    public final PullTaskPullerAccountStateChangedSideEffect roleEvents;
    public final AtomicInteger wakeStatements = new AtomicInteger();
    private final AccountStateEventServiceImpl accountEvents;

    /** 创建可与账号域双开关用例共享的事务、Mapper 与固定任务 11 / 执行行 111。 */
    public PullTaskBothSwitchesOffH2Support(AccountTakeoverH2Support accounts) throws Exception {
        accountSupport = accounts;
        accountSupport.properties.setEnabled(false);
        properties.setEnabled(false);
        jdbc = accounts.jdbc;
        for (String ddl : PullTaskNormalLinkSchema.all()) { jdbc.execute(ddl); }
        jdbc.execute("ALTER TABLE account_state ADD COLUMN IF NOT EXISTS risk_status INT");
        var config = new MyBatisConfig();
        var interceptor = config.mybatisPlusInterceptor(config.tenantLineHandler());
        interceptor.addInnerInterceptor(new InnerInterceptor() {
            @Override public void beforeUpdate(Executor executor, MappedStatement statement, Object parameter) {
                if (statement.getId().endsWith(".wakeForReconnectedRole")) { wakeStatements.incrementAndGet(); }
            }
        });
        var sql = new SqlSessionTemplate(PullTaskNormalLinkH2Support.sqlSessionFactory(accounts.dataSource, interceptor,
                "mapper/task/PullTaskMapper.xml", "mapper/task/PullTaskGroupExecutionMapper.xml",
                "mapper/task/PullTaskGroupAccountMapper.xml", "mapper/task/PullTaskStandardSettingMapper.xml",
                "mapper/task/PullTaskStandardGroupSettingMapper.xml", "mapper/task/PullTaskAccountActionMapper.xml",
                "mapper/task/PullTaskPullCallMapper.xml"));
        executions = sql.getMapper(PullTaskGroupExecutionMapper.class);
        roles = sql.getMapper(PullTaskGroupAccountMapper.class);
        var tasks = sql.getMapper(PullTaskMapper.class);
        var settings = sql.getMapper(PullTaskStandardSettingMapper.class);
        var actions = sql.getMapper(PullTaskAccountActionMapper.class);
        lookup = new AccountProtocolLookupServiceImpl(accounts.accounts, accounts.properties);
        var gate = new PullTaskCreatorOfflineGate(roles, lookup, accounts.online, properties,
                accounts.transactionManager, mock(PullTaskGroupExecutionFailureService.class));
        var sticky = new PullTaskStickyPullerTransactionService(executions, roles,
                sql.getMapper(PullTaskPullCallMapper.class), sql.getMapper(PullTaskPullCallMemberAttemptMapper.class), lookup);
        var accountStates = accounts.transactional(new PullTaskPullerAccountStateServiceImpl(roles, executions,
                new PullTaskPullerAccountStateResources(sticky, event -> { },
                        new PullTaskExecutionDispatchTrigger(mock(PullTaskExecutionDispatchScheduler.class)), properties, lookup)),
                PullTaskPullerAccountStateService.class);
        roleEvents = new PullTaskPullerAccountStateChangedSideEffect(accountStates, lookup);
        recovery = accounts.transactional(new PullTaskResourceRecoveryTransactionService(tasks, settings, roles,
                new PullTaskResourceRecoveryResources(executions, lookup, new PullTaskStationSelectionService(roles, lookup),
                        mock(GroupExecutionAccountSelector.class), actions, new PullTaskManagerAdminCandidateSelector(),
                        properties, accountStates, gate)), PullTaskResourceRecoveryTransactionService.class);
        creates = accounts.transactional(new PullTaskGroupCreateTransactionService(
                new PullTaskGroupCreatePersistence(tasks, settings, sql.getMapper(PullTaskStandardGroupSettingMapper.class),
                        executions, roles, actions), new PullTaskGroupCreateResources(lookup, mock(GroupCreatePort.class),
                        mock(GroupInvitePort.class), mock(GroupLinkRegistryService.class), mock(PullTaskGroupProfileDispatcher.class),
                        mock(FixedAccountGroupMetadataPort.class)), mock(PullTaskCreatorDeletionTransactionService.class), gate),
                PullTaskGroupCreateTransactionService.class);
        accountEvents = accounts.transactional(new AccountStateEventServiceImpl(accounts.accounts, accounts.states,
                accounts.ipProxyService, List.of(), accounts.policy), AccountStateEventServiceImpl.class);
        seedTask();
    }

    /** 普通在线账号真实接收被挤事件，实证账号开关 false 产生旧 6 号离线状态。 */
    public void offlineAccount(long id, Long groupId) {
        accountSupport.account(id, 2, 1, null, null);
        jdbc.update("UPDATE account SET account_group_id=? WHERE id=?", groupId, id);
        accountEvents.applyStateChanged(new AccountStateChangedEvent(1L,id,"account-"+id,
                "ONLINE","LOGIN_REPLACED",2_000L,"LOGIN_REPLACED",440,"protocol",null,null));
    }

    /** 创建可被真实账号 Mapper 查询选中的在线账号。 */
    public void onlineAccount(long id, Long groupId) {
        accountSupport.account(id, 2, 1, null, null);
        jdbc.update("UPDATE account SET account_group_id=? WHERE id=?", groupId, id);
    }

    /** 写入固定角色，返回真实角色主键；首槽在每种角色中独立编号。 */
    public long role(long id, int kind, int availability, int membership) {
        jdbc.update("INSERT INTO pull_task_group_account(tenant_id,task_id,group_execution_id,account_id,account_phone,"
                + "role_type,role_seq,availability_status,membership_status,unavailable_reason_code,created_at,updated_at) "
                + "VALUES(1,11,111,?,'15500000000',?,1,?,?,?,100,100)", id,kind,availability,membership,
                availability == PullTaskGroupAccountAvailability.OFFLINE.code() ? "ACCOUNT_NOT_ONLINE" : null);
        return jdbc.queryForObject("SELECT id FROM pull_task_group_account WHERE account_id=? AND role_type=?",Long.class,id,kind);
    }

    /** 当前执行行，默认持有本测试固定租约。 */
    public PullTaskGroupExecution candidate() { return executions.selectById(111L); }

    /** 设置角色资源等待状态，同时恢复租约，供一次真实资源恢复调用。 */
    public void waitFor(int stage, int role) {
        jdbc.update("UPDATE pull_task_group_execution SET execution_status=3,stage=?,wait_resource_type=?,"
                + "lock_owner=?,lock_expires_at=?,next_run_at=0 WHERE id=111",stage,role,OWNER,NOW+10_000L);
    }

    private void seedTask() {
        jdbc.update("INSERT INTO pull_task(id,tenant_id,task_name,mode,creation_mode,status,config_json,created_at,updated_at) "
                + "VALUES(11,1,'task','NORMAL_LINK','NEW_GROUP','EXECUTING','{}',100,100)");
        jdbc.update("INSERT INTO pull_task_standard_setting(tenant_id,task_id,material_admin_timing,pull_count_min,pull_count_max,"
                + "pull_interval_seconds,puller_count_per_group,station_count_per_call,concurrent_group_count,required_manager_count,"
                + "manager_group_id,puller_group_id,station_group_id,manager_group_name,puller_group_name,station_group_name,created_at,updated_at) "
                + "VALUES(1,11,1,1,2,1,1,1,1,1,88,89,90,'manager','puller','station',100,100)");
        jdbc.update("INSERT INTO pull_task_standard_group_setting(tenant_id,task_id,is_group_setting_enabled,setting_timing,group_name,"
                + "group_description,is_material_filename_as_group_name,edit_permission_mode,mute_mode,link_permission_mode,"
                + "disappearing_message_mode,created_at,updated_at) VALUES(1,11,1,1,'subject','description',0,0,0,2,0,100,100)");
        jdbc.update("INSERT INTO pull_task_group_execution(id,tenant_id,task_id,seq,source_file_index,source_file_name,execution_status,"
                + "stage,create_step,manual_paused,next_run_at,lock_owner,lock_expires_at,version,create_operation_id,group_subject,"
                + "group_jid,created_at,updated_at) VALUES(111,1,11,1,1,'members.txt',2,9,2,0,0,?,?,2,'create-111','subject','group@g.us',100,100)",
                OWNER,NOW+10_000L);
    }
}
