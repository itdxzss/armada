package com.armada.task.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.boot.config.MyBatisConfig;
import com.armada.group.service.GroupFolderService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolCommandOutboxEnqueueResult;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.PullTaskAccountActionMapper;
import com.armada.task.service.impl.PullTaskGroupProfileDispatcher;
import com.armada.task.mapper.PullTaskGroupAccountMapper;
import com.armada.task.mapper.PullTaskGroupExecutionMapper;
import com.armada.task.mapper.PullTaskMapper;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.mapper.PullTaskNormalLinkH2Support;
import com.armada.task.mapper.PullTaskPullCallMapper;
import com.armada.task.mapper.PullTaskPullCallMemberAttemptMapper;
import com.armada.task.mapper.PullTaskPullWaveMapper;
import com.armada.task.mapper.PullTaskStandardGroupSettingMapper;
import com.armada.task.mapper.PullTaskStandardSettingMapper;
import com.armada.task.model.entity.PullTaskAccountAction;
import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskPullCall;
import com.armada.task.model.entity.PullTaskPullCallMemberAttempt;
import com.armada.task.model.entity.PullTaskPullWave;
import com.armada.task.model.enums.PullTaskAccountActionType;
import com.armada.task.model.enums.PullTaskActionStatus;
import com.armada.task.model.enums.PullTaskExecutionStage;
import com.armada.task.model.enums.PullTaskExecutionStatus;
import com.armada.task.model.enums.PullTaskGroupAccountAdminStatus;
import com.armada.task.model.enums.PullTaskGroupAccountMembershipStatus;
import com.armada.task.model.enums.PullTaskGroupAccountRole;
import com.armada.task.model.enums.PullTaskGroupSettingTiming;
import com.armada.task.model.enums.PullTaskMaterialPullStatus;
import com.armada.task.model.enums.PullTaskParticipantAttemptStatus;
import com.armada.task.model.enums.PullTaskParticipantType;
import com.armada.task.model.enums.PullTaskPullCallStatus;
import com.armada.task.model.enums.PullTaskPullWaveStatus;
import com.armada.task.model.enums.PullTaskPullWaveType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

/**
 * 「群信息设置」在执行链路上何时下发、是否下发。
 *
 * <p>断言只看 {@code pull_task_account_action} 里有没有 {@code APPLY_GROUP_SETTINGS} 动作行：
 * 由哪个类、在哪个方法里写这行属于实现细节，业务口径只管时机和是否下发。</p>
 *
 * <p>时机口径（业务方 2026-08-18 拍板）：</p>
 * <ul>
 *   <li>{@code BEFORE_PULL} 在管理员提权完成、拉手尚未开始拉人的 {@code MANAGER_PULLER_CONTACT}
 *       阶段下发。</li>
 *   <li>{@code AFTER_PULL} 在这个群刚拉完人的那一刻下发，也就是 {@code PULL_EXECUTION} 波次结算、
 *       执行行离开拉人阶段的那一步。它明确不是收口时机：拉完人与收口之间还隔着
 *       {@code MATERIAL_ADMIN} 料子提权，拖到收口再改群资料会让运营在群里看到旧设置的窗口
 *       白白拉长一整个提权阶段。</li>
 *   <li>总开关关闭时全程不产生，填过的值一律不算数。</li>
 * </ul>
 *
 * <p>接线口径：被测服务一律由 {@code @Import} 交给 Spring 按构造器装配，本类只提供 Bean。
 * 实现方给这些服务新增一个依赖时，只要该依赖在本配置里有 Bean 就无需改动测试；上一轮用写死参数
 * {@code new} 出服务，导致实现接不进去、断言永远变不绿。所有任务域 Mapper（含
 * {@code PullTaskStandardGroupSettingMapper}）都已注册，就是为此留的余地。</p>
 */
@SpringJUnitConfig(PullTaskGroupSettingsApplyTimingIntegrationTest.TestConfig.class)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        inheritListeners = false)
class PullTaskGroupSettingsApplyTimingIntegrationTest {

    private static final long TASK_ID = 100L;
    private static final long EXECUTION_ID = 501L;
    private static final long MATERIAL_ID = 601L;
    private static final String OWNER = "worker-1";
    private static final long TENANT_ID = 7L;

    @Autowired private DataSource dataSource;
    @Autowired private PullTaskGroupExecutionMapper executionMapper;
    @Autowired private PullTaskGroupAccountMapper groupAccountMapper;
    @Autowired private PullTaskAccountActionMapper actionMapper;
    @Autowired private PullTaskPullCallMapper callMapper;
    @Autowired private PullTaskPullCallMemberAttemptMapper attemptMapper;
    @Autowired private PullTaskPullWaveMapper waveMapper;
    @Autowired private AccountProtocolLookupService accountLookup;
    @Autowired private ProtocolCommandOutboxService outboxService;
    @Autowired private PullTaskManagerPullerContactTransactionService contactService;
    @Autowired private PullTaskPullWaveSettlementTransactionService settlementService;
    @Autowired private PullTaskClosingTransactionService closingService;

    @BeforeEach
    void setUp() throws SQLException {
        reset(accountLookup, outboxService);
        seedProtocolAccounts();
        resetFixture();
    }

    /**
     * 重建库与整套 fixture。
     *
     * <p>「不产生动作」这类否定断言必须配一个同 fixture 的对照组，否则功能一行没写时它也是绿的。
     * 对照组跑完要把库清干净再跑否定组，本方法就是给这个用的。</p>
     */
    private void resetFixture() throws SQLException {
        resetFixture(true);
    }

    /**
     * @param withManager 是否给这条执行行放一个已在群内、已提权的任务管理员；
     *                    传 {@code false} 构造「群里没有可用管理员」的场景
     */
    private void resetFixture(boolean withManager) throws SQLException {
        TenantContext.set(TENANT_ID);
        PullTaskNormalLinkH2Support.resetSchema(dataSource);
        insertParentAndSetting();
        insertExecution();
        if (withManager) {
            insertManagerRole();
        }
        insertMaterial();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---------- 断言 1：拉人前 ----------

    @Test
    @DisplayName("开关开+设置顺序=拉人前：提权完成后、拉人开始前产生群设置动作")
    void beforePullTimingAppliesGroupSettingsBeforePullingStarts() {
        seedGroupSetting(1, PullTaskGroupSettingTiming.BEFORE_PULL);
        seedSucceededMemberAddAction();

        runContactStage();

        assertThat(groupSettingsActions()).hasSize(1);
    }

    // ---------- 断言 2：拉完人后，且不许拖到收口 ----------

    @Test
    @DisplayName("开关开+设置顺序=拉完人后：拉人开始前不产生群设置动作")
    void afterPullTimingCreatesNothingBeforePulling() throws SQLException {
        // 对照组：同一个阶段、同一套 fixture，改成拉人前必须产生一条。
        seedGroupSetting(1, PullTaskGroupSettingTiming.BEFORE_PULL);
        seedSucceededMemberAddAction();
        runContactStage();
        assertThat(groupSettingsActions()).hasSize(1);

        resetFixture();
        seedGroupSetting(1, PullTaskGroupSettingTiming.AFTER_PULL);
        seedSucceededMemberAddAction();
        runContactStage();

        assertThat(groupSettingsActions()).isEmpty();
    }

    @Test
    @DisplayName("开关开+设置顺序=拉完人后：这个群刚拉完人就产生群设置动作")
    void afterPullTimingAppliesGroupSettingsWhenPullWaveLeavesPullExecution() {
        seedGroupSetting(1, PullTaskGroupSettingTiming.AFTER_PULL);

        settleFinishedPullWave();

        assertThat(groupSettingsActions()).hasSize(1);
    }

    /**
     * 收口不是「群信息设置」的时机。
     *
     * <p>对照组先证明这套 fixture 在波次结算那一刻确实会产生动作，然后换一条从未结算过波次、
     * 直接摆到 {@code CLOSING} 的执行行跑收口。只要实现把群设置拖到收口才发，动作行就会在
     * 第二段冒出来——那正是要禁止的偷懒做法。</p>
     */
    @Test
    @DisplayName("开关开+设置顺序=拉完人后：收口阶段不得补发群设置动作")
    void afterPullTimingDoesNotDeferGroupSettingsToClosingStage() throws SQLException {
        seedGroupSetting(1, PullTaskGroupSettingTiming.AFTER_PULL);
        settleFinishedPullWave();
        assertThat(groupSettingsActions()).hasSize(1);

        resetFixture();
        seedGroupSetting(1, PullTaskGroupSettingTiming.AFTER_PULL);
        runClosingStage();

        assertThat(groupSettingsActions()).isEmpty();
    }

    // ---------- 断言 3：开关关，全程不产生 ----------

    @Test
    @DisplayName("开关关+设置顺序=拉人前：不产生群设置动作")
    void disabledToggleCreatesNothingInContactStage() throws SQLException {
        // 对照组：同一套 fixture 开着开关必须产生一条，否则「没产生」只说明功能没写。
        seedGroupSetting(1, PullTaskGroupSettingTiming.BEFORE_PULL);
        seedSucceededMemberAddAction();
        runContactStage();
        assertThat(groupSettingsActions()).hasSize(1);

        resetFixture();
        seedGroupSetting(0, PullTaskGroupSettingTiming.BEFORE_PULL);
        seedSucceededMemberAddAction();
        runContactStage();

        assertThat(groupSettingsActions()).isEmpty();
    }

    @Test
    @DisplayName("开关关+设置顺序=拉完人后：拉完人也不产生群设置动作")
    void disabledToggleCreatesNothingAtPullWaveSettlement() throws SQLException {
        seedGroupSetting(1, PullTaskGroupSettingTiming.AFTER_PULL);
        settleFinishedPullWave();
        assertThat(groupSettingsActions()).hasSize(1);

        resetFixture();
        seedGroupSetting(0, PullTaskGroupSettingTiming.AFTER_PULL);
        settleFinishedPullWave();

        assertThat(groupSettingsActions()).isEmpty();
    }

    // ---------- 断言 8：群里没有可用管理员就整体跳过 ----------

    /**
     * 没有可用任务管理员时整块跳过，不报错也不阻断执行行。
     *
     * <p>群设置全靠任务管理员的群管理员身份去改，人不在或没提上权就一项也改不了。业务口径是
     * 跳过而不是失败：拉人本身早已完成，为一个改不了的群资料把执行行卡住或标失败，运营看到的
     * 是一条假失败的行。</p>
     *
     * <p>验证点在波次结算而不是联系人阶段：联系人阶段本来就会因为放开加人权限拿不到管理员而
     * 主动等待，「不阻断」在那里无从谈起；拉完人这一刻执行行必须继续往料子提权走。</p>
     */
    @Test
    @DisplayName("群里没有可用管理员：不产生群设置动作，也不阻断执行行")
    void missingManagerSkipsGroupSettingsWithoutBlockingExecution() throws SQLException {
        // 对照组：同一套 fixture 有管理员时必须产生一条。
        seedGroupSetting(1, PullTaskGroupSettingTiming.AFTER_PULL);
        settleFinishedPullWave();
        assertThat(groupSettingsActions()).hasSize(1);

        resetFixture(false);
        seedGroupSetting(1, PullTaskGroupSettingTiming.AFTER_PULL);

        // settleFinishedPullWave 内含自检：结算必须返回 ADVANCED 且落到 MATERIAL_ADMIN。
        // 实现若在没有管理员时抛异常或让执行行停在 PULL_EXECUTION，这一步就会红。
        settleFinishedPullWave();

        assertThat(groupSettingsActions()).isEmpty();
    }

    // ---------- 断言 7 的下半段：成功后不重复下发 ----------
    /**
     * 群设置成功之后再跑一遍本阶段，不得产生第二行动作、也不得再发一条命令。
     *
     * <p>本阶段会被反复调度（等联系人、等拉手都会回到这里），一次成功之后每轮再改一次群资料
     * 就是在拿运营的群做无意义的写操作，还会撞上 WhatsApp 的频控。</p>
     */
    @Test
    @DisplayName("群设置成功后重跑本阶段：动作行仍只有一条，不再下发第二条命令")
    void succeededGroupSettingsAreNotSubmittedAgainOnStageRerun() {
        seedGroupSetting(1, PullTaskGroupSettingTiming.BEFORE_PULL);
        seedSucceededMemberAddAction();

        runContactStage();
        markGroupSettingsActionSucceeded();
        runContactStage();

        assertThat(groupSettingsActions()).hasSize(1);
        assertThat(groupSettingsActions().get(0).getActionStatus())
                .isEqualTo(PullTaskActionStatus.SUCCESS.code());
    }

    /** 跑完管理—拉手联系人阶段的两步：群设置门控与联系人准备。 */
    private void runContactStage() {
        contactService.ensureGroupSettings(execution(), OWNER, 710L);
        // 门控已改写执行行版本号，第二步必须重新取行，否则乐观锁 CAS 必然落空。
        contactService.prepare(execution(), OWNER, 720L);
    }

    /**
     * 把这条执行行的拉人波次收干净，让它在结算里离开 PULL_EXECUTION。
     *
     * <p>这一步就是「这个群刚拉完人」：波次内 attempt 全部闭合、没有可重试候选，结算把执行行
     * 推进到料子提权阶段。群设置动作必须在这一步之内产生，而不是等到 CLOSING。</p>
     */
    private void settleFinishedPullWave() {
        execute("UPDATE pull_task_group_execution SET stage="
                + PullTaskExecutionStage.PULL_EXECUTION.code() + " WHERE id=" + EXECUTION_ID);
        PullTaskPullWave wave = insertCollectingWave();

        PullTaskExecutionDispatchResult result =
                settlementService.settle(execution(), wave, OWNER, 2_000L);

        // fixture 自检：结算必须真的推进了执行行，且落点不是收口。否则「没产生动作」会是波次没收
        // 干净造成的假红，实现方照着改也永远变不绿。
        assertThat(result).isEqualTo(PullTaskExecutionDispatchResult.ADVANCED);
        assertThat(execution().getStage())
                .isEqualTo(PullTaskExecutionStage.MATERIAL_ADMIN.code());
    }

    /** 把执行行摆到收口阶段并跑一次收口。 */
    private void runClosingStage() {
        execute("UPDATE pull_task_group_execution SET stage="
                + PullTaskExecutionStage.CLOSING.code() + " WHERE id=" + EXECUTION_ID);
        closingService.close(execution(), OWNER, 2_000L);
    }

    private PullTaskGroupExecution execution() {
        TenantContext.set(TENANT_ID);
        return executionMapper.selectById(EXECUTION_ID);
    }

    private List<PullTaskAccountAction> groupSettingsActions() {
        TenantContext.set(TENANT_ID);
        return actionMapper.selectByExecutionAndType(
                EXECUTION_ID, PullTaskAccountActionType.APPLY_GROUP_SETTINGS.code());
    }

    /** 把已产生的群设置动作行直接置为成功，模拟协议层回调已收敛。 */
    private void markGroupSettingsActionSucceeded() {
        execute("UPDATE pull_task_account_action SET action_status="
                + PullTaskActionStatus.SUCCESS.code() + ", result_at=800, updated_at=800"
                + " WHERE group_execution_id=" + EXECUTION_ID + " AND action_type="
                + PullTaskAccountActionType.APPLY_GROUP_SETTINGS.code());
    }

    /** 加人权限已确认，群设置门已打开，本阶段不会再卡在加人权限上。 */
    private void seedSucceededMemberAddAction() {
        long managerRoleId = managerRole().getId();
        execute("INSERT INTO pull_task_account_action "
                + "(tenant_id, task_id, group_execution_id, action_type, "
                + "actor_group_account_id, target_group_account_id, action_status, "
                + "command_id, attempt_no, created_at, updated_at) VALUES "
                + "(" + TENANT_ID + ", " + TASK_ID + ", " + EXECUTION_ID + ", "
                + PullTaskAccountActionType.OPEN_MEMBER_ADD.code() + ", "
                + managerRoleId + ", " + managerRoleId + ", "
                + PullTaskActionStatus.SUCCESS.code() + ", "
                + "'cmd-member-add-1', 1, 600, 600)");
    }

    /**
     * 写一份填满的「群信息设置」，只有总开关和设置顺序按用例变化。
     *
     * <p>关闭态也照样把值填满：这样「不产生动作」才证明是开关拦住的，而不是因为没填值。</p>
     */
    private void seedGroupSetting(int enabled, PullTaskGroupSettingTiming timing) {
        execute("INSERT INTO pull_task_standard_group_setting "
                + "(tenant_id, task_id, is_group_setting_enabled, setting_timing, group_name, "
                + "is_material_filename_as_group_name, group_description, "
                + "is_auto_unmute_after_task, is_auto_close_invite_after_task, "
                + "edit_permission_mode, mute_mode, link_permission_mode, "
                + "disappearing_message_mode, created_at, updated_at) "
                + "VALUES (" + TENANT_ID + ", " + TASK_ID + ", " + enabled + ", "
                + timing.code() + ", '客户群', 0, '群说明', 0, 0, 1, 1, 2, 2, 100, 100)");
    }

    /** 一波已派发完、结果全部回写的初始波次；结算后不留重试候选。 */
    private PullTaskPullWave insertCollectingWave() {
        PullTaskPullWave wave = new PullTaskPullWave();
        wave.setTaskId(TASK_ID);
        wave.setGroupExecutionId(EXECUTION_ID);
        wave.setWaveNo(1);
        wave.setWaveType(PullTaskPullWaveType.INITIAL.code());
        wave.setWaveStatus(PullTaskPullWaveStatus.COLLECTING.code());
        wave.setPlannedCallCount(1);
        wave.setNextDispatchAt(1_000L);
        wave.setDispatchCompletedAt(1_000L);
        wave.setCreatedAt(100L);
        wave.setUpdatedAt(100L);
        waveMapper.insertInitialized(wave);
        execute("UPDATE pull_task_group_execution SET active_pull_wave_id=" + wave.getId()
                + " WHERE id=" + EXECUTION_ID);

        PullTaskPullCall call = new PullTaskPullCall();
        call.setTaskId(TASK_ID);
        call.setGroupExecutionId(EXECUTION_ID);
        call.setPullWaveId(wave.getId());
        call.setCallSeq(1);
        call.setWaveCallSeq(1);
        call.setPullerGroupAccountId(901L);
        call.setPullerAccountId(902L);
        call.setPullerAssignmentSeq(3L);
        call.setPlannedMaterialCount(1);
        call.setPlannedStationCount(0);
        call.setIdempotencyKey("group-settings-call-1");
        call.setCreatedAt(100L);
        call.setUpdatedAt(100L);
        callMapper.insertPlanned(call);

        PullTaskPullCallMemberAttempt attempt = new PullTaskPullCallMemberAttempt();
        attempt.setTaskId(TASK_ID);
        attempt.setGroupExecutionId(EXECUTION_ID);
        attempt.setPullCallId(call.getId());
        attempt.setPullWaveId(wave.getId());
        attempt.setParticipantType(PullTaskParticipantType.MATERIAL.code());
        attempt.setParticipantRefId(MATERIAL_ID);
        attempt.setTargetPhone("8613900000001");
        attempt.setTargetJid("8613900000001@s.whatsapp.net");
        attempt.setPullerGroupAccountId(901L);
        attempt.setPullerAssignmentSeq(3L);
        attempt.setAttemptNo(1);
        attempt.setFailureCountBefore(0L);
        attempt.setCreatedAt(100L);
        attempt.setUpdatedAt(100L);
        attemptMapper.insertPlanned(attempt);

        closeAttemptAsSuccess(attempt);
        return waveMapper.selectById(wave.getId());
    }

    private void closeAttemptAsSuccess(PullTaskPullCallMemberAttempt attempt) {
        execute("UPDATE pull_task_pull_call_member_attempt SET lifecycle_status="
                + PullTaskParticipantAttemptStatus.CLOSED.code()
                + ", active_slot=NULL, protocol_outcome='SUCCESS', execution_state='STARTED'"
                + ", reason_code='TIMEOUT', result_at=1500, updated_at=1500 WHERE id="
                + attempt.getId());
        execute("UPDATE pull_task_pull_call SET call_status="
                + PullTaskPullCallStatus.WRITTEN_BACK.code()
                + ", result_at=1500, updated_at=1500 WHERE id=" + attempt.getPullCallId());
        execute("UPDATE pull_task_material_member SET pull_status="
                + PullTaskMaterialPullStatus.SUCCESS.code()
                + ", pull_call_id=NULL, active_pull_attempt_id=NULL WHERE id=" + MATERIAL_ID);
    }

    private void seedProtocolAccounts() {
        ProtocolAccountRef manager = new ProtocolAccountRef(
                901L, ProtocolBackend.WEB, "manager-901", "8613800000901");
        ProtocolAccountRef puller = new ProtocolAccountRef(
                902L, ProtocolBackend.WEB, "puller-902", "8613800000902");
        when(accountLookup.findOnlineNormalPullersByGroupId(89L)).thenReturn(List.of(puller));
        when(accountLookup.findActiveProtocolRefs(anyList())).thenReturn(List.of(manager, puller));
        when(outboxService.enqueuePullTaskContactSaveCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        "pull-task:100", List.of("cmd-contact-1"), 1));
        when(outboxService.enqueuePullTaskGroupSettingsCommands(anyList()))
                .thenReturn(new ProtocolCommandOutboxEnqueueResult(
                        "pull-task:100", List.of("cmd-settings-1"), 1));
    }

    /** 已在群内且提权成功的任务管理员，是任何群设置命令的执行人。 */
    private void insertManagerRole() {
        PullTaskGroupAccount row = new PullTaskGroupAccount();
        row.setTaskId(TASK_ID);
        row.setGroupExecutionId(EXECUTION_ID);
        row.setAccountId(901L);
        row.setAccountPhone("8613800000901");
        row.setRoleType(PullTaskGroupAccountRole.MANAGER.code());
        row.setRoleSeq(1);
        row.setSourceType(1);
        row.setSelectionMode(1);
        row.setEntryMode(1);
        row.setCreatedAt(100L);
        row.setUpdatedAt(100L);
        groupAccountMapper.insert(row);
        groupAccountMapper.updateMembership(row.getId(),
                PullTaskGroupAccountMembershipStatus.IN_GROUP.code(), 550L, 550L);
        groupAccountMapper.transitionAdminStatus(
                row.getId(), List.of(PullTaskGroupAccountAdminStatus.PENDING.code()),
                PullTaskGroupAccountAdminStatus.SUCCESS.code(), 550L);
    }

    private PullTaskGroupAccount managerRole() {
        TenantContext.set(TENANT_ID);
        return groupAccountMapper.selectByExecutionAndRole(
                EXECUTION_ID, PullTaskGroupAccountRole.MANAGER.code()).get(0);
    }

    private void insertExecution() {
        execute("INSERT INTO pull_task_group_execution "
                + "(id, tenant_id, task_id, seq, normalized_link, invite_code, "
                + "source_link_line_no, source_file_index, source_file_name, group_jid, "
                + "execution_status, stage, active_puller_group_account_id, "
                + "puller_assignment_seq, lock_owner, lock_expires_at, version, "
                + "created_at, updated_at) VALUES ("
                + EXECUTION_ID + ", " + TENANT_ID + ", " + TASK_ID + ", 1, "
                + "'chat.whatsapp.com/AAAA', 'AAAA', 1, 1, "
                + "'印度料子包.txt', '120363group@g.us', "
                + PullTaskExecutionStatus.EXECUTING.code() + ", "
                + PullTaskExecutionStage.MANAGER_PULLER_CONTACT.code()
                + ", 901, 3, '" + OWNER + "', 100000, 6, 100, 100)");
    }

    /**
     * 一条需要提权的料子。
     *
     * <p>{@code admin_required=1} 让波次结算后的落点是 {@code MATERIAL_ADMIN} 而不是
     * {@code CLOSING}，这样「拉完人就发」和「拖到收口才发」之间隔着一个真实阶段，
     * 时机断言才有区分度。</p>
     */
    private void insertMaterial() {
        execute("INSERT INTO pull_task_material_member "
                + "(id, tenant_id, group_execution_id, member_seq, source_line_no, "
                + "normalized_phone, admin_required, pull_status, admin_status, "
                + "created_at, updated_at) VALUES ("
                + MATERIAL_ID + ", " + TENANT_ID + ", " + EXECUTION_ID
                + ", 1, 1, '8613900000001', 1, 0, 1, 100, 100)");
    }

    private void insertParentAndSetting() {
        execute("INSERT INTO pull_task "
                + "(id, tenant_id, task_type, task_name, mode, status, config_json, "
                + "created_at, updated_at) VALUES "
                + "(" + TASK_ID + ", " + TENANT_ID
                + ", 'STANDARD', 'task', 'NORMAL_LINK', 'EXECUTING', '{}', 100, 100)");
        execute("INSERT INTO pull_task_standard_setting "
                + "(tenant_id, task_id, auto_start, material_admin_timing, pull_count_min, "
                + "pull_count_max, pull_interval_seconds, puller_count_per_group, "
                + "station_count_per_call, concurrent_group_count, puller_risk_minutes, "
                + "required_manager_count, manager_group_id, puller_group_id, station_group_id, "
                + "manager_group_name, puller_group_name, station_group_name, "
                + "created_at, updated_at) "
                + "VALUES (" + TENANT_ID + ", " + TASK_ID
                + ", 1, 1, 1, 2, 1, 1, 1, 1, 0, 1, 88, 89, 90, "
                + "'manager', 'puller', 'station', 100, 100)");
    }

    private void execute(String sql) {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException("H2 fixture 执行失败: " + sql, exception);
        }
    }

    /**
     * 被测阶段服务全部走 {@code @Import} 由 Spring 构造。
     *
     * <p>本配置只负责提供 Bean，不负责决定谁依赖谁：新增依赖时实现方改自己的构造器即可，
     * 不需要回来改测试。</p>
     */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({
            MyBatisConfig.class,
            PullTaskManagerPullerContactResources.class,
            PullTaskManagerPullerContactTransactionService.class,
            PullTaskStationSelectionService.class,
            PullTaskPullWavePlanningSelection.class,
            PullTaskPullWavePlanningResources.class,
            PullTaskPullWavePlanningTransactionService.class,
            PullTaskPullWaveSettlementResources.class,
            PullTaskPullWaveSettlementTransactionService.class,
            PullTaskParentCompletionService.class,
            PullTaskClosingTransactionService.class,
            PullTaskGroupProfileDispatcher.class
    })
    static class TestConfig {

        @Bean
        DataSource dataSource() {
            return PullTaskNormalLinkH2Support.dataSource("pull_task_group_settings_apply_test");
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
                    "mapper/task/PullTaskStandardSettingMapper.xml",
                    "mapper/task/PullTaskStandardGroupSettingMapper.xml",
                    "mapper/task/PullTaskGroupExecutionMapper.xml",
                    "mapper/task/PullTaskGroupAccountMapper.xml",
                    "mapper/task/PullTaskAccountActionMapper.xml",
                    "mapper/task/PullTaskMaterialMemberMapper.xml",
                    "mapper/task/PullTaskPullCallMapper.xml",
                    "mapper/task/PullTaskPullCallMemberAttemptMapper.xml",
                    "mapper/task/PullTaskPullWaveMapper.xml");
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        PullTaskMapper taskMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMapper.class);
        }

        @Bean
        PullTaskStandardSettingMapper settingMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskStandardSettingMapper.class);
        }

        /** 「群信息设置」Mapper：实现要读总开关、设置顺序和 12 个字段，必须能注进去。 */
        @Bean
        PullTaskStandardGroupSettingMapper groupSettingMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskStandardGroupSettingMapper.class);
        }

        @Bean
        PullTaskGroupExecutionMapper executionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupExecutionMapper.class);
        }

        @Bean
        PullTaskGroupAccountMapper groupAccountMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskGroupAccountMapper.class);
        }

        @Bean
        PullTaskAccountActionMapper actionMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskAccountActionMapper.class);
        }

        @Bean
        PullTaskMaterialMemberMapper materialMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskMaterialMemberMapper.class);
        }

        @Bean
        PullTaskPullCallMapper callMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMapper.class);
        }

        @Bean
        PullTaskPullCallMemberAttemptMapper attemptMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullCallMemberAttemptMapper.class);
        }

        @Bean
        PullTaskPullWaveMapper waveMapper(SqlSessionTemplate template) {
            return template.getMapper(PullTaskPullWaveMapper.class);
        }

        @Bean
        AccountProtocolLookupService accountLookup() {
            return mock(AccountProtocolLookupService.class);
        }

        @Bean
        ProtocolCommandOutboxService outboxService() {
            return mock(ProtocolCommandOutboxService.class);
        }

        @Bean
        PullTaskExecutionDispatchProperties properties() {
            return new PullTaskExecutionDispatchProperties();
        }

        /** 取下限，消掉随机拉人数对断言的干扰。 */
        @Bean
        PullTaskBatchSizeSelector batchSizeSelector() {
            return new PullTaskBatchSizeSelector((minimum, maximum) -> minimum);
        }

        @Bean
        GroupFolderService groupFolderService() {
            return mock(GroupFolderService.class);
        }
    }
}
