package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.armada.boot.config.MyBatisConfig;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.mapper.GroupLinkPreviewMapper;
import com.armada.group.mapper.GroupMetadataSyncTaskMapper;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.dto.AccountGroupMembershipChangedEvent;
import com.armada.group.model.entity.GroupMetadataSyncTask;
import com.armada.group.model.enums.GroupMetadataSyncStatus;
import com.armada.group.model.enums.GroupMetadataSyncTrigger;
import com.armada.group.model.vo.AccountGroupMembershipChangeSet;
import com.armada.group.model.vo.AccountGroupMembershipSnapshot;
import com.armada.group.model.vo.GroupClassificationCandidate;
import com.armada.group.model.vo.GroupClassificationPlan;
import com.armada.group.service.GroupClassificationService;
import com.armada.group.service.GroupExecutionAccountSelector;
import com.armada.group.service.GroupLinkRegistryService;
import com.armada.group.service.impl.GroupCurrentSnapshotMySqlTestSupport.RecordingDataSource;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 在真实 MySQL RR 下锁定账号群兼容句柄的集合写入和并发锁序。 */
@Testcontainers
class GroupLinkRegistryBatchMySqlTest {

    private static final Logger log = LoggerFactory.getLogger(GroupLinkRegistryBatchMySqlTest.class);
    private static final long TENANT_ID = 7L;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("armada_group_registry")
            .withUsername("armada")
            .withPassword("armada")
            .withCommand(
                    "--transaction-isolation=REPEATABLE-READ",
                    "--innodb-deadlock-detect=ON",
                    "--innodb-lock-wait-timeout=5");

    private static JdbcTemplate jdbc;
    private static JdbcTemplate transactionalJdbc;
    private static RecordingDataSource recordingDataSource;
    private static TransactionTemplate transactionTemplate;
    private static GroupLinkRegistryServiceImpl registry;
    private static GroupClassificationService classificationService;
    private static GroupMetadataSyncTaskServiceImpl metadataTaskService;
    private static AccountGroupMembershipReportPhaseService phaseService;
    private static AccountGroupCurrentSnapshotMapper currentSnapshotMapper;
    private static GroupLinkMapper groupLinkMapper;
    private static GroupMetadataSyncTaskMapper metadataTaskMapper;
    private static AccountGroupCurrentSnapshotPersistenceImpl currentSnapshotPersistence;
    private static com.armada.marketing.service.MarketingNewGroupImmediateSendService marketingService;
    private static GroupExecutionAccountSelector executionAccountSelector;

    @BeforeAll
    static void configureMysqlAndProductionMapper() throws Exception {
        var grant = MYSQL.execInContainer(
                "mysql", "-uroot", "-p" + MYSQL.getPassword(), "-e",
                "GRANT SELECT ON performance_schema.* TO 'armada'@'%'");
        if (grant.getExitCode() != 0) {
            throw new IllegalStateException(
                    "无法为 MySQL 并发夹具开启锁等待观测: " + grant.getStderr());
        }
        DriverManagerDataSource rawDataSource = new DriverManagerDataSource();
        rawDataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        rawDataSource.setUrl(MYSQL.getJdbcUrl());
        rawDataSource.setUsername(MYSQL.getUsername());
        rawDataSource.setPassword(MYSQL.getPassword());
        jdbc = new JdbcTemplate(rawDataSource);
        createSchema();

        recordingDataSource = new RecordingDataSource(rawDataSource);
        transactionalJdbc = new JdbcTemplate(recordingDataSource);
        transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(recordingDataSource));
        transactionTemplate.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
        SqlSessionTemplate session = buildSqlSessionTemplate(recordingDataSource);
        groupLinkMapper = session.getMapper(GroupLinkMapper.class);
        currentSnapshotMapper = session.getMapper(AccountGroupCurrentSnapshotMapper.class);
        executionAccountSelector = new GroupExecutionAccountSelector(
                session.getMapper(AccountGroupMembershipMapper.class));
        registry = new GroupLinkRegistryServiceImpl(
                groupLinkMapper,
                mock(GroupLinkPreviewMapper.class),
                mock(AccountGroupCurrentSnapshotPersistenceImpl.class));
        metadataTaskMapper = session.getMapper(GroupMetadataSyncTaskMapper.class);
        metadataTaskService = new GroupMetadataSyncTaskServiceImpl(
                metadataTaskMapper, 2_000L, 120_000L);
        classificationService = new GroupClassificationServiceImpl(
                currentSnapshotMapper,
                groupLinkMapper,
                mock(GroupLinkRegistryService.class),
                metadataTaskService);
        AccountGroupMembershipSnapshotServiceImpl snapshotService =
                new AccountGroupMembershipSnapshotServiceImpl(
                        groupLinkMapper,
                        mock(GroupLinkPreviewMapper.class),
                        registry,
                        classificationService);
        currentSnapshotPersistence = mock(AccountGroupCurrentSnapshotPersistenceImpl.class);
        marketingService = mock(
                com.armada.marketing.service.MarketingNewGroupImmediateSendService.class);
        phaseService = new AccountGroupMembershipReportPhaseService(
                currentSnapshotMapper,
                groupLinkMapper,
                snapshotService,
                classificationService,
                currentSnapshotPersistence,
                marketingService,
                metadataTaskService);
    }

    @AfterAll
    static void clearTenantContext() {
        TenantContext.clear();
    }

    @BeforeEach
    void resetData() {
        org.mockito.Mockito.reset(currentSnapshotPersistence, marketingService);
        jdbc.update("DELETE FROM group_metadata_sync_task");
        jdbc.update("DELETE FROM wa_account_group_binding");
        jdbc.update("DELETE FROM wa_group_participant");
        jdbc.update("DELETE FROM group_link_preview");
        jdbc.update("DELETE FROM group_link");
        jdbc.update("DELETE FROM wa_group_profile");
        jdbc.update("DELETE FROM wa_group_invite");
        jdbc.update("DELETE FROM wa_group");
        jdbc.update("DELETE FROM account_state");
        jdbc.update("DELETE FROM account_group_sync_state");
        jdbc.update("DELETE FROM account");
        jdbc.update("""
                INSERT INTO account (
                  id, tenant_id, ws_phone, protocol_id, protocol_account_id, deleted_at
                ) VALUES (10, ?, '15550000001', 'WEB', 'acc-10', NULL)
                """, TENANT_ID);
        jdbc.update("""
                INSERT INTO account_group_sync_state (
                  tenant_id, account_id, baseline_state, baseline_completeness,
                  baseline_group_count, baseline_captured_at, last_sync_requested_at,
                  last_complete_at
                ) VALUES (?, 10, 1, 0, 0, NULL, NULL, NULL)
                """, TENANT_ID);
        recordingDataSource.reset();
    }

    @Test
    void snapshotOf400ExistingGroupsUsesFourStatementsWithStableIdPrelock() {
        Map<String, Long> expectedIds = new TreeMap<>();
        for (int index = 0; index < 400; index++) {
            String groupJid = groupJid(index);
            long id = index + 1L;
            expectedIds.put(groupJid, id);
            jdbc.update("""
                    INSERT INTO group_link (
                      id, tenant_id, link_url, group_name, origin, membership_state,
                      sync_protocol_mask, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, 5, 2, 1, 1000, 1000)
                    """, id, TENANT_ID, "wa://group/" + groupJid, "old-" + index);
        }
        List<String> reversedJids = new ArrayList<>(expectedIds.keySet());
        Collections.reverse(reversedJids);
        Map<String, String> observedNames = new LinkedHashMap<>();
        reversedJids.forEach(groupJid -> observedNames.put(groupJid, "new-" + groupJid));
        recordingDataSource.reset();

        Map<String, Long> actual = inTransaction(
                () -> registry.registerAccountObservedGroups(
                        observedNames, ProtocolBackend.WEB, 2_000L));

        assertThat(actual).containsExactlyEntriesOf(expectedIds);
        assertThat(recordingDataSource.statements())
                .hasSize(4)
                .noneMatch(statement -> statement.startsWith("BATCH "));
        assertThat(recordingDataSource.statements().get(2))
                .startsWith("INSERT INTO GROUP_LINK")
                .contains("ON DUPLICATE KEY UPDATE");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM group_link WHERE group_name LIKE 'new-%'", Integer.class))
                .isEqualTo(400);
    }

    @Test
    void coldClassificationOf400GroupsUsesFiveStatementsAndWarmReplayUsesOne() {
        List<GroupClassificationCandidate> candidates = new ArrayList<>();
        for (int index = 0; index < 400; index++) {
            long id = index + 1L;
            String groupJid = groupJid(index);
            seedGroupLink(id, groupJid);
            candidates.add(new GroupClassificationCandidate(id, groupJid, "group-" + index));
        }
        Collections.reverse(candidates);
        recordingDataSource.reset();

        long coldStartedAt = System.nanoTime();
        inTransaction(() -> {
            classificationService.captureHistoricalBaseline(
                    candidates, ProtocolBackend.WEB, 2_000L);
            return null;
        });
        long coldElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - coldStartedAt);
        List<String> coldStatements = recordingDataSource.statements();

        assertThat(coldStatements).hasSize(5);
        assertThat(coldStatements)
                .filteredOn(statement -> statement.startsWith("UPDATE GROUP_LINK"))
                .singleElement();
        assertThat(coldStatements)
                .filteredOn(statement -> statement.startsWith("INSERT INTO GROUP_METADATA_SYNC_TASK"))
                .singleElement();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM group_link WHERE is_historical = 1", Integer.class))
                .isEqualTo(400);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM group_metadata_sync_task", Integer.class))
                .isEqualTo(400);

        recordingDataSource.reset();
        long warmStartedAt = System.nanoTime();
        inTransaction(() -> {
            classificationService.captureHistoricalBaseline(
                    candidates, ProtocolBackend.WEB, 3_000L);
            return null;
        });
        long warmElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - warmStartedAt);

        assertThat(recordingDataSource.statements())
                .hasSize(1)
                .allMatch(statement -> statement.startsWith("SELECT"));
        assertThat(jdbc.queryForObject(
                "SELECT MAX(updated_at) FROM group_metadata_sync_task", Long.class))
                .isEqualTo(2_000L);
        assertThat(coldElapsedMs).isLessThan(5_000L);
        assertThat(warmElapsedMs).isLessThan(2_000L);
        log.info("400群分类实测 coldStatements={} coldMs={} warmStatements={} warmMs={}",
                coldStatements.size(), coldElapsedMs,
                recordingDataSource.statements().size(), warmElapsedMs);
    }

    @Test
    void completePendingCompatibilityPhaseOf400GroupsUsesEightStatementsAndWarmReplayUsesSeven() {
        AccountGroupsReportedEvent event = eventWithGroups(400);
        recordingDataSource.reset();

        long coldStartedAt = System.nanoTime();
        AccountGroupMembershipReportPhaseService.CompatibilityPhaseResult cold = inTransaction(
                () -> phaseService.prepareCompatibility(
                        event, ProtocolBackend.WEB, true, true, 2_000L));
        long coldElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - coldStartedAt);
        List<String> coldStatements = recordingDataSource.statements();

        assertThat(cold.accepted()).isTrue();
        assertThat(cold.groups()).hasSize(400);
        assertThat(coldStatements).hasSize(8);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM group_link WHERE is_historical = 1", Integer.class))
                .isEqualTo(400);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM group_metadata_sync_task", Integer.class))
                .as("phase1 不得暴露尚无当前绑定的调度任务")
                .isZero();

        recordingDataSource.reset();
        long warmStartedAt = System.nanoTime();
        AccountGroupMembershipReportPhaseService.CompatibilityPhaseResult warm = inTransaction(
                () -> phaseService.prepareCompatibility(
                        event, ProtocolBackend.WEB, true, true, 2_000L));
        long warmElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - warmStartedAt);

        assertThat(warm.accepted()).isTrue();
        assertThat(warm.groups()).hasSize(400);
        assertThat(recordingDataSource.statements()).hasSize(7);
        assertThat(coldElapsedMs).isLessThan(5_000L);
        assertThat(warmElapsedMs).isLessThan(2_000L);
        log.info("400群第一阶段实测 coldStatements={} coldMs={} warmStatements={} warmMs={}",
                coldStatements.size(), coldElapsedMs,
                recordingDataSource.statements().size(), warmElapsedMs);
    }

    @Test
    void failedPhaseTwoLeavesNoSchedulableTaskAndWarmReplayCreatesDueClassificationTask() {
        AccountGroupsReportedEvent event = eventWithGroups(1);
        AccountGroupMembershipReportPhaseService.CompatibilityPhaseResult first = inTransaction(
                () -> phaseService.prepareCompatibility(
                        event, ProtocolBackend.WEB, true, true, 2_000L));
        AccountGroupMembershipSnapshot group = first.groups().get(0);

        assertThat(first.classificationPlan().newlyPersisted())
                .containsEntry(group.groupLinkId(), GroupMetadataSyncTrigger.BASELINE_CAPTURED);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM group_metadata_sync_task", Integer.class)).isZero();

        org.mockito.Mockito.when(currentSnapshotPersistence.replaceVisibleGroups(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new CannotAcquireLockException("simulated phase2 timeout"))
                .thenReturn(new AccountGroupMembershipChangeSet(List.of(group), List.of(group)));

        assertThatThrownBy(() -> inTransaction(() -> phaseService.applyCurrentSnapshot(
                event, true, 2_000L, first.groups(), first.classificationPlan(), true)))
                .isInstanceOf(CannotAcquireLockException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM group_metadata_sync_task", Integer.class)).isZero();

        AccountGroupMembershipReportPhaseService.CompatibilityPhaseResult replay = inTransaction(
                () -> phaseService.prepareCompatibility(
                        event, ProtocolBackend.WEB, true, true, 2_000L));
        assertThat(replay.classificationPlan().newlyPersisted()).isEmpty();
        assertThat(replay.classificationPlan().desired())
                .containsEntry(group.groupLinkId(), GroupMetadataSyncTrigger.BASELINE_CAPTURED);

        inTransaction(() -> phaseService.applyCurrentSnapshot(
                event, true, 2_000L, replay.groups(), replay.classificationPlan(), true));

        GroupMetadataSyncTask task = inTransaction(
                () -> metadataTaskMapper.selectByGroupLinkId(group.groupLinkId()));
        assertThat(task.getStatus()).isEqualTo(GroupMetadataSyncStatus.PENDING.code());
        assertThat(task.getTriggerSource())
                .isEqualTo(GroupMetadataSyncTrigger.BASELINE_CAPTURED.code());
        assertThat(task.getNextRunAt()).isEqualTo(2_000L);
        assertThat(metadataTaskService.findDue(2_001L, 10))
                .extracting(GroupMetadataSyncTask::getGroupLinkId)
                .contains(group.groupLinkId());
    }

    @Test
    void lateSchedulerDeferCannotHideTaskAfterPhaseTwoMakesBindingExecutable() {
        String groupJid = groupJid(901);
        seedGroupLink(1L, groupJid);
        inTransaction(() -> {
            metadataTaskService.reconcileClassifications(
                    Map.of(1L, GroupMetadataSyncTrigger.BASELINE_CAPTURED), 2_000L);
            return null;
        });

        GroupMetadataSyncTask staleSchedulerRead = metadataTaskService.findDue(2_001L, 10).get(0);
        assertThat(inTransaction(() -> executionAccountSelector.find(1L, 0))).isEmpty();

        jdbc.update("""
                INSERT INTO wa_group (id, tenant_id, group_jid, created_at, updated_at)
                VALUES (100, ?, ?, 2000, 2000)
                """, TENANT_ID, groupJid);
        jdbc.update("UPDATE group_link SET group_id = 100 WHERE tenant_id = ? AND id = 1", TENANT_ID);
        jdbc.update("""
                INSERT INTO wa_group_participant
                  (id, tenant_id, group_id, presence_status, role)
                VALUES (200, ?, 100, 1, 1)
                """, TENANT_ID);
        jdbc.update("""
                INSERT INTO wa_account_group_binding
                  (id, tenant_id, account_id, group_id, participant_id, last_observed_at)
                VALUES (300, ?, 10, 100, 200, 2500)
                """, TENANT_ID);
        jdbc.update("""
                INSERT INTO account_state
                  (tenant_id, account_id, login_state, account_state)
                VALUES (?, 10, ?, ?)
                """, TENANT_ID, AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL);
        assertThat(inTransaction(() -> executionAccountSelector.find(1L, 0))).isPresent();

        inTransaction(() -> {
            metadataTaskService.defer(staleSchedulerRead, 3_000L);
            return null;
        });

        GroupMetadataSyncTask afterLateDefer = inTransaction(
                () -> metadataTaskMapper.selectByGroupLinkId(1L));
        assertThat(afterLateDefer.getStatus()).isEqualTo(GroupMetadataSyncStatus.PENDING.code());
        assertThat(metadataTaskService.findDue(3_001L, 10))
                .extracting(GroupMetadataSyncTask::getGroupLinkId)
                .contains(1L);
    }

    @RepeatedTest(10)
    void fourConcurrentOverlappingSnapshotsReuseOneHandlePerJidWithoutDeadlock()
            throws Exception {
        int workers = 4;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Map<String, Long>>> futures = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                int offset = worker * 25;
                ProtocolBackend backend = worker % 2 == 0
                        ? ProtocolBackend.WEB : ProtocolBackend.ANDROID;
                futures.add(executor.submit(() -> {
                    Map<String, String> groups = groups(offset, 100);
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return inTransaction(() -> registry.registerAccountObservedGroups(
                            groups, backend, 3_000L + offset));
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            Map<String, Long> resolvedIds = new TreeMap<>();
            for (Future<Map<String, Long>> future : futures) {
                Map<String, Long> resolved = future.get(15, TimeUnit.SECONDS);
                resolved.forEach((groupJid, groupLinkId) -> {
                    Long previous = resolvedIds.putIfAbsent(groupJid, groupLinkId);
                    assertThat(previous).isIn(null, groupLinkId);
                });
            }
            assertThat(resolvedIds).hasSize(175);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM group_link WHERE tenant_id = ?",
                    Integer.class, TENANT_ID)).isEqualTo(175);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM (
                      SELECT link_url
                      FROM group_link
                      WHERE tenant_id = ?
                      GROUP BY link_url
                      HAVING COUNT(*) > 1
                    ) duplicate_urls
                    """, Integer.class, TENANT_ID)).isZero();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentBlankLoserNeverOverwritesCommittedRealGroupName() throws Exception {
        String groupJid = groupJid(900);
        CountDownLatch namedWritten = new CountDownLatch(1);
        CountDownLatch releaseNamedCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Map<String, Long>> named = executor.submit(() ->
                inTransaction(() -> {
                    Map<String, Long> resolved = registry.registerAccountObservedGroups(
                            Map.of(groupJid, "真实群名"), ProtocolBackend.ANDROID, 3_001L);
                    namedWritten.countDown();
                    assertThat(releaseNamedCommit.await(5, TimeUnit.SECONDS)).isTrue();
                    return resolved;
                }));
            Future<Map<String, Long>> blank = executor.submit(() -> {
                assertThat(namedWritten.await(5, TimeUnit.SECONDS)).isTrue();
                return inTransaction(() -> registry.registerAccountObservedGroups(
                        Map.of(groupJid, "  "), ProtocolBackend.WEB, 3_000L));
            });
            assertThat(namedWritten.await(5, TimeUnit.SECONDS)).isTrue();
            awaitMysqlLockWait();
            releaseNamedCommit.countDown();

            assertThat(named.get(10, TimeUnit.SECONDS).get(groupJid))
                    .isEqualTo(blank.get(10, TimeUnit.SECONDS).get(groupJid));
            assertThat(jdbc.queryForObject(
                    "SELECT group_name FROM group_link WHERE tenant_id = ? AND link_url = ?",
                    String.class, TENANT_ID, "wa://group/" + groupJid))
                    .isEqualTo("真实群名");
        } finally {
            releaseNamedCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void batchPrelocksExistingHandlesByIdWhenJidOrderIsReversed() throws Exception {
        String lowerJid = groupJid(910);
        String higherJid = groupJid(911);
        seedGroupLink(2L, lowerJid);
        seedGroupLink(1L, higherJid);
        CountDownLatch idOneLocked = new CountDownLatch(1);
        CountDownLatch batchEntered = new CountDownLatch(1);
        CountDownLatch releaseSecondLock = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> phaseLike = executor.submit(() -> inTransaction(() -> {
                groupLinkMapper.selectAccountObservedByIdsForUpdate(TENANT_ID, 1L, List.of(1L));
                idOneLocked.countDown();
                assertThat(releaseSecondLock.await(5, TimeUnit.SECONDS)).isTrue();
                groupLinkMapper.selectAccountObservedByIdsForUpdate(TENANT_ID, 1L, List.of(2L));
                return null;
            }));
            Future<Map<String, Long>> batch = executor.submit(() -> {
                assertThat(idOneLocked.await(5, TimeUnit.SECONDS)).isTrue();
                batchEntered.countDown();
                return inTransaction(() -> registry.registerAccountObservedGroups(
                        Map.of(lowerJid, "lower", higherJid, "higher"),
                        ProtocolBackend.WEB,
                        4_000L));
            });
            assertThat(batchEntered.await(5, TimeUnit.SECONDS)).isTrue();
            awaitMysqlLockWait();
            releaseSecondLock.countDown();

            phaseLike.get(10, TimeUnit.SECONDS);
            assertThat(batch.get(10, TimeUnit.SECONDS))
                    .containsEntry(lowerJid, 2L)
                    .containsEntry(higherJid, 1L);
        } finally {
            releaseSecondLock.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void preciseAddAndPhaseTwoBothLockGroupBeforeBindingAndTask() throws Exception {
        String groupJid = groupJid(920);
        seedGroupLink(1L, groupJid);
        jdbc.update("""
                INSERT INTO wa_account_group_binding (
                  id, tenant_id, account_id, group_id, participant_id, last_observed_at
                ) VALUES (300, ?, 10, 100, 200, 1000)
                """, TENANT_ID);
        AccountGroupMembershipSnapshot legacyGroup = new AccountGroupMembershipSnapshot(
                1L, groupJid, "锁序群", "wa://group/" + groupJid, true);
        AccountGroupsReportedEvent reportedEvent = new AccountGroupsReportedEvent(
                TENANT_ID, 10L, "acc-10", 4_000L,
                List.of(new AccountGroupsReportedEvent.Group(
                        groupJid, "锁序群", 20, null, null, false, false, null)),
                "phase-event", "test", true, 0);
        GroupClassificationPlan phasePlan = new GroupClassificationPlan(
                Map.of(1L, GroupMetadataSyncTrigger.BASELINE_CAPTURED),
                Map.of(1L, GroupMetadataSyncTrigger.BASELINE_CAPTURED));
        AtomicBoolean preciseBindingWritten = new AtomicBoolean();
        AtomicBoolean phaseBindingWritten = new AtomicBoolean();
        CountDownLatch preciseGroupLocked = new CountDownLatch(1);
        CountDownLatch releasePreciseBinding = new CountDownLatch(1);
        CountDownLatch phaseEntered = new CountDownLatch(1);

        org.mockito.Mockito.doAnswer(invocation -> {
            preciseGroupLocked.countDown();
            assertThat(releasePreciseBinding.await(5, TimeUnit.SECONDS)).isTrue();
            transactionalJdbc.update("""
                    UPDATE wa_account_group_binding
                    SET last_observed_at = 3000
                    WHERE tenant_id = ? AND id = 300
                    """, TENANT_ID);
            preciseBindingWritten.set(true);
            return null;
        }).when(currentSnapshotPersistence).applySelfMembershipChanged(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.when(currentSnapshotPersistence.replaceVisibleGroups(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> {
                    transactionalJdbc.update("""
                            UPDATE wa_account_group_binding
                            SET last_observed_at = 4000
                            WHERE tenant_id = ? AND id = 300
                            """, TENANT_ID);
                    phaseBindingWritten.set(true);
                    return new AccountGroupMembershipChangeSet(
                            List.of(legacyGroup), List.of(legacyGroup));
                });

        GroupClassificationService preciseClassification = mock(GroupClassificationService.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(preciseBindingWritten)
                    .as("精确 add 必须先写 W 再写 TASK")
                    .isTrue();
            metadataTaskService.enqueue(
                    1L, GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED, 3_000L);
            return null;
        }).when(preciseClassification).classifyMembershipAdded(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
        AccountGroupMembershipStatusServiceImpl preciseService =
                new AccountGroupMembershipStatusServiceImpl(
                        mock(AccountGroupMembershipMapper.class),
                        currentSnapshotMapper,
                        registry,
                        preciseClassification,
                        currentSnapshotPersistence);

        GroupMetadataSyncTaskServiceImpl phaseTasks = org.mockito.Mockito.spy(metadataTaskService);
        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(phaseBindingWritten)
                    .as("phase2 必须先写 W 再写 TASK")
                    .isTrue();
            metadataTaskService.enqueueClassifications(
                    invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(phaseTasks).enqueueClassifications(
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyLong());
        AccountGroupMembershipReportPhaseService orderedPhase =
                new AccountGroupMembershipReportPhaseService(
                        currentSnapshotMapper,
                        groupLinkMapper,
                        mock(com.armada.group.service.AccountGroupMembershipSnapshotService.class),
                        mock(GroupClassificationService.class),
                        currentSnapshotPersistence,
                        marketingService,
                        phaseTasks);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> precise = executor.submit(() -> inTransaction(() -> {
                preciseService.applyMembershipChanged(new AccountGroupMembershipChangedEvent(
                        TENANT_ID, 10L, "acc-10", groupJid,
                        "add", 3_000L, "precise-event", "test"));
                return null;
            }));
            assertThat(preciseGroupLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<AccountGroupMembershipChangeSet> phase = executor.submit(() -> {
                phaseEntered.countDown();
                return inTransaction(() -> orderedPhase.applyCurrentSnapshot(
                        reportedEvent, true, 4_000L, List.of(legacyGroup),
                        phasePlan, false));
            });
            assertThat(phaseEntered.await(5, TimeUnit.SECONDS)).isTrue();
            awaitMysqlLockWait();
            assertThat(phaseBindingWritten)
                    .as("phase2 在 GL 等待期间不得先持有 W")
                    .isFalse();
            assertThat(jdbc.queryForObject("""
                    SELECT last_observed_at
                    FROM wa_account_group_binding
                    WHERE tenant_id = ? AND id = 300
                    """, Long.class, TENANT_ID)).isEqualTo(1_000L);

            releasePreciseBinding.countDown();
            precise.get(10, TimeUnit.SECONDS);
            assertThat(phase.get(10, TimeUnit.SECONDS).currentGroups())
                    .containsExactly(legacyGroup);
            assertThat(preciseBindingWritten).isTrue();
            assertThat(phaseBindingWritten).isTrue();
            assertThat(jdbc.queryForObject("""
                    SELECT last_observed_at
                    FROM wa_account_group_binding
                    WHERE tenant_id = ? AND id = 300
                    """, Long.class, TENANT_ID)).isEqualTo(4_000L);
            assertThat(inTransaction(() -> metadataTaskMapper.selectByGroupLinkId(1L)))
                    .extracting(GroupMetadataSyncTask::getStatus)
                    .isEqualTo(GroupMetadataSyncStatus.PENDING.code());
        } finally {
            releasePreciseBinding.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void classificationAndOnlineResumeUseTaskPrimaryKeyOrderWhenIdsAreInverse()
            throws Exception {
        String firstJid = groupJid(930);
        String secondJid = groupJid(931);
        seedGroupLink(1L, firstJid);
        seedGroupLink(2L, secondJid);
        jdbc.update("""
                INSERT INTO wa_group (id, tenant_id, group_jid, created_at, updated_at)
                VALUES (100, ?, ?, 1000, 1000), (101, ?, ?, 1000, 1000)
                """, TENANT_ID, firstJid, TENANT_ID, secondJid);
        jdbc.update("UPDATE group_link SET group_id = 100 WHERE tenant_id = ? AND id = 1", TENANT_ID);
        jdbc.update("UPDATE group_link SET group_id = 101 WHERE tenant_id = ? AND id = 2", TENANT_ID);
        jdbc.update("""
                INSERT INTO wa_group_participant
                  (id, tenant_id, group_id, presence_status, role)
                VALUES (200, ?, 100, 1, 1), (201, ?, 101, 1, 1)
                """, TENANT_ID, TENANT_ID);
        jdbc.update("""
                INSERT INTO wa_account_group_binding
                  (id, tenant_id, account_id, group_id, participant_id, last_observed_at)
                VALUES (300, ?, 10, 100, 200, 1000), (301, ?, 10, 101, 201, 1000)
                """, TENANT_ID, TENANT_ID);
        jdbc.update("""
                INSERT INTO account_state
                  (tenant_id, account_id, login_state, account_state)
                VALUES (?, 10, ?, ?)
                """, TENANT_ID, AccountLoginStateCode.ONLINE, AccountStateCode.NORMAL);
        jdbc.update("""
                INSERT INTO group_metadata_sync_task (
                  id, tenant_id, group_link_id, status, trigger_source, attempt_count,
                  next_run_at, rerun_requested, completed_scope_mask, created_at, updated_at
                ) VALUES
                  (1, ?, 2, ?, ?, 0, NULL, FALSE, 1, 1000, 1000),
                  (2, ?, 1, ?, ?, 0, NULL, FALSE, 1, 1000, 1000)
                """,
                TENANT_ID,
                GroupMetadataSyncStatus.DEFERRED.code(),
                GroupMetadataSyncTrigger.BASELINE_CAPTURED.code(),
                TENANT_ID,
                GroupMetadataSyncStatus.DEFERRED.code(),
                GroupMetadataSyncTrigger.BASELINE_CAPTURED.code());

        CountDownLatch blockerLockedTaskTwo = new CountDownLatch(1);
        CountDownLatch releaseTaskTwo = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<Void> blocker = executor.submit(() -> inTransaction(() -> {
                transactionalJdbc.queryForObject(
                        "SELECT id FROM group_metadata_sync_task WHERE id = 2 FOR UPDATE",
                        Long.class);
                blockerLockedTaskTwo.countDown();
                assertThat(releaseTaskTwo.await(15, TimeUnit.SECONDS)).isTrue();
                return null;
            }));
            assertThat(blockerLockedTaskTwo.await(5, TimeUnit.SECONDS)).isTrue();

            Future<Void> classification = executor.submit(() -> inTransaction(() -> {
                metadataTaskService.reconcileClassifications(
                        Map.of(
                                1L, GroupMetadataSyncTrigger.BASELINE_CAPTURED,
                                2L, GroupMetadataSyncTrigger.POST_CONTROL_DISCOVERED),
                        2_000L);
                return null;
            }));
            awaitMysqlLockWaitCount(1);

            Future<Void> onlineResume = executor.submit(() -> inTransaction(() -> {
                metadataTaskService.resumeDeferredInviteCodeForAccount(10L, 2_100L);
                return null;
            }));
            awaitMysqlLockWaitCount(2);
            releaseTaskTwo.countDown();

            blocker.get(10, TimeUnit.SECONDS);
            classification.get(10, TimeUnit.SECONDS);
            onlineResume.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForList(
                    "SELECT status FROM group_metadata_sync_task ORDER BY id", Integer.class))
                    .containsExactly(
                            GroupMetadataSyncStatus.PENDING.code(),
                            GroupMetadataSyncStatus.PENDING.code());
        } finally {
            releaseTaskTwo.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @RepeatedTest(10)
    void fourConcurrentOverlappingColdClassificationsFinishWithoutDeadlock()
            throws Exception {
        for (int index = 0; index < 175; index++) {
            seedGroupLink(index + 1L, groupJid(index));
            jdbc.update("""
                    INSERT INTO group_metadata_sync_task (
                      tenant_id, group_link_id, status, trigger_source, attempt_count,
                      next_run_at, rerun_requested, completed_scope_mask, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, 0, NULL, FALSE, 0, 500, 500)
                    """,
                    TENANT_ID,
                    index + 1L,
                    GroupMetadataSyncStatus.RUNNING.code(),
                    GroupMetadataSyncTrigger.BASELINE_CAPTURED.code());
        }
        int workers = 4;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                int offset = worker * 25;
                futures.add(executor.submit(() -> {
                    List<GroupClassificationCandidate> candidates =
                            classificationCandidates(offset, 100);
                    ready.countDown();
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return inTransaction(() -> {
                        classificationService.captureHistoricalBaseline(
                                candidates, ProtocolBackend.WEB, 4_000L + offset);
                        return null;
                    });
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM group_link WHERE is_historical = 1", Integer.class))
                    .isEqualTo(175);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM group_metadata_sync_task", Integer.class))
                    .isEqualTo(175);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM group_metadata_sync_task WHERE rerun_requested = TRUE",
                    Integer.class)).isEqualTo(175);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM group_metadata_sync_task task
                    INNER JOIN group_link link
                      ON link.tenant_id = task.tenant_id
                     AND link.id = task.group_link_id
                    WHERE task.updated_at != link.updated_at
                    """, Integer.class)).isZero();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static Map<String, String> groups(int offset, int count) {
        List<String> groupJids = new ArrayList<>();
        for (int index = offset; index < offset + count; index++) {
            groupJids.add(groupJid(index));
        }
        Collections.reverse(groupJids);
        Map<String, String> groups = new LinkedHashMap<>();
        groupJids.forEach(groupJid -> groups.put(groupJid, "group-" + groupJid));
        return groups;
    }

    private static List<GroupClassificationCandidate> classificationCandidates(
            int offset,
            int count) {
        List<GroupClassificationCandidate> candidates = new ArrayList<>();
        for (int index = offset; index < offset + count; index++) {
            candidates.add(new GroupClassificationCandidate(
                    index + 1L, groupJid(index), "group-" + index));
        }
        Collections.reverse(candidates);
        return candidates;
    }

    private static AccountGroupsReportedEvent eventWithGroups(int count) {
        List<AccountGroupsReportedEvent.Group> groups = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            groups.add(new AccountGroupsReportedEvent.Group(
                    groupJid(index), "group-" + index, 20,
                    null, null, false, false, null));
        }
        Collections.reverse(groups);
        return new AccountGroupsReportedEvent(
                TENANT_ID, 10L, "acc-10", 2_000L,
                groups, "evt-400", "test", true, 0);
    }

    private static String groupJid(int index) {
        return "120363900" + String.format("%06d", index) + "@g.us";
    }

    private static void seedGroupLink(long id, String groupJid) {
        jdbc.update("""
                INSERT INTO group_link (
                  id, tenant_id, link_url, group_name, origin, membership_state,
                  sync_protocol_mask, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 5, 2, 1, 1000, 1000)
                """, id, TENANT_ID, "wa://group/" + groupJid, "old-" + groupJid);
    }

    private static <T> T inTransaction(java.util.concurrent.Callable<T> action) {
        TenantContext.set(TENANT_ID);
        try {
            return transactionTemplate.execute(status -> {
                try {
                    return action.call();
                } catch (RuntimeException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
        } finally {
            TenantContext.clear();
        }
    }

    private static void awaitMysqlLockWait() {
        awaitMysqlLockWaitCount(1);
    }

    private static void awaitMysqlLockWaitCount(int expectedWaits) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waits = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM performance_schema.data_lock_waits", Integer.class);
            if (waits != null && waits >= expectedWaits) {
                return;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        throw new AssertionError("未观察到预期数量的 MySQL 行锁等待: " + expectedWaits);
    }

    private static void createSchema() {
        jdbc.execute("""
                CREATE TABLE account (
                  id BIGINT NOT NULL,
                  tenant_id BIGINT NOT NULL,
                  ws_phone VARCHAR(64) DEFAULT NULL,
                  protocol_id VARCHAR(32) DEFAULT NULL,
                  protocol_account_id VARCHAR(128) DEFAULT NULL,
                  deleted_at BIGINT DEFAULT NULL,
                  PRIMARY KEY (id),
                  KEY idx_account_tenant (tenant_id, id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE account_group_sync_state (
                  tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL,
                  baseline_state TINYINT DEFAULT NULL,
                  baseline_completeness TINYINT DEFAULT NULL,
                  baseline_group_count INT DEFAULT NULL,
                  baseline_captured_at BIGINT DEFAULT NULL,
                  last_sync_requested_at BIGINT DEFAULT NULL,
                  last_complete_at BIGINT DEFAULT NULL,
                  PRIMARY KEY (account_id),
                  KEY idx_sync_tenant (tenant_id, account_id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE account_state (
                  tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL,
                  login_state INT NOT NULL,
                  account_state INT NOT NULL,
                  PRIMARY KEY (account_id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE wa_group (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  tenant_id BIGINT NOT NULL,
                  group_jid VARCHAR(128) NOT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  deleted_at BIGINT DEFAULT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uq_wa_group_identity (tenant_id, group_jid)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE wa_group_invite (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  tenant_id BIGINT NOT NULL,
                  invite_code VARCHAR(128) DEFAULT NULL,
                  PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE wa_group_profile (
                  group_id BIGINT NOT NULL,
                  tenant_id BIGINT NOT NULL,
                  current_invite_id BIGINT DEFAULT NULL,
                  member_snapshot_at BIGINT DEFAULT NULL,
                  PRIMARY KEY (group_id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE wa_group_participant (
                  id BIGINT NOT NULL,
                  tenant_id BIGINT NOT NULL,
                  group_id BIGINT NOT NULL,
                  presence_status TINYINT NOT NULL,
                  role TINYINT DEFAULT NULL,
                  PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE wa_account_group_binding (
                  id BIGINT NOT NULL,
                  tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL,
                  group_id BIGINT NOT NULL,
                  participant_id BIGINT NOT NULL,
                  last_observed_at BIGINT DEFAULT NULL,
                  PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE group_link_preview (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  tenant_id BIGINT NOT NULL,
                  group_link_id BIGINT NOT NULL,
                  owner_phone VARCHAR(64) DEFAULT NULL,
                  PRIMARY KEY (id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE group_link (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  group_id BIGINT DEFAULT NULL,
                  group_invite_id BIGINT DEFAULT NULL,
                  tenant_id BIGINT NOT NULL,
                  link_url VARCHAR(512) NOT NULL,
                  group_name VARCHAR(128) DEFAULT NULL,
                  label_id BIGINT DEFAULT NULL,
                  folder_id BIGINT DEFAULT NULL,
                  import_batch_id BIGINT DEFAULT NULL,
                  origin TINYINT NOT NULL DEFAULT 1,
                  membership_state TINYINT NOT NULL DEFAULT 1,
                  is_historical TINYINT NOT NULL DEFAULT 0,
                  is_post_control TINYINT NOT NULL DEFAULT 0,
                  sync_protocol_mask TINYINT NOT NULL DEFAULT 0,
                  remark VARCHAR(512) DEFAULT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  created_by BIGINT DEFAULT NULL,
                  deleted_at BIGINT DEFAULT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uq_url (tenant_id, link_url),
                  KEY idx_group_link_group (tenant_id, group_id, id)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE group_metadata_sync_task (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  tenant_id BIGINT NOT NULL,
                  group_link_id BIGINT NOT NULL,
                  status TINYINT NOT NULL,
                  trigger_source TINYINT NOT NULL,
                  attempt_count INT NOT NULL DEFAULT 0,
                  next_run_at BIGINT DEFAULT NULL,
                  lease_until BIGINT DEFAULT NULL,
                  execution_account_id BIGINT DEFAULT NULL,
                  rerun_requested TINYINT NOT NULL DEFAULT 0,
                  current_command_id VARCHAR(128) DEFAULT NULL,
                  requested_scope_mask TINYINT NOT NULL DEFAULT 0,
                  completed_scope_mask TINYINT NOT NULL DEFAULT 0,
                  candidate_cursor INT NOT NULL DEFAULT 0,
                  result_deadline_at BIGINT DEFAULT NULL,
                  last_started_at BIGINT DEFAULT NULL,
                  last_success_at BIGINT DEFAULT NULL,
                  last_error_code VARCHAR(64) DEFAULT NULL,
                  last_error_message VARCHAR(512) DEFAULT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uq_group_metadata_sync_task (tenant_id, group_link_id)
                ) ENGINE=InnoDB
                """);
    }

    private static SqlSessionTemplate buildSqlSessionTemplate(DataSource dataSource)
            throws Exception {
        MyBatisConfig myBatisConfig = new MyBatisConfig();
        MybatisPlusInterceptor interceptor =
                myBatisConfig.mybatisPlusInterceptor(myBatisConfig.tenantLineHandler());
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setUseGeneratedKeys(true);

        MybatisSqlSessionFactoryBean factoryBean = new MybatisSqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setPlugins(interceptor);
        factoryBean.setMapperLocations(
                new ClassPathResource("mapper/group/GroupLinkMapper.xml"),
                new ClassPathResource("mapper/group/AccountGroupCurrentSnapshotMapper.xml"),
                new ClassPathResource("mapper/group/AccountGroupMembershipMapper.xml"),
                new ClassPathResource("mapper/group/GroupMetadataSyncTaskMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) {
            throw new IllegalStateException("无法创建群兼容句柄测试 SqlSessionFactory");
        }
        return new SqlSessionTemplate(factory);
    }
}
