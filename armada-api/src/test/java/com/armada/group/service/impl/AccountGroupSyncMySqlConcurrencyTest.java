package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.mapper.AccountGroupMembershipMapper;
import com.armada.group.mapper.GroupLinkHealthMapper;
import com.armada.group.mapper.GroupLinkMapper;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.group.model.entity.AccountGroupMembership;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 使用 MySQL 8.4 InnoDB 验证账号群列表同步的真实 Mapper 和事务锁序。
 *
 * <p>H2 不实现 supremum record、插入意向锁和 InnoDB 死锁检测，因此这里只用
 * Testcontainers 承担并发证明；测试表保持生产唯一键形状，业务写入走生产 XML。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class AccountGroupSyncMySqlConcurrencyTest {

    private static final long TENANT_ID = 7L;
    private static final int THREAD_COUNT = 4;
    private static final int GROUPS_PER_THREAD = 12;
    private static final int CROSSING_GROUP_POOL_SIZE = 24;

    private static final String LEGACY_PREVIEW_UPSERT = """
            INSERT INTO group_link_preview (
              tenant_id, group_link_id, group_jid, wa_subject, member_size,
              last_preview_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              group_jid = VALUES(group_jid),
              wa_subject = VALUES(wa_subject),
              member_size = VALUES(member_size),
              last_preview_at = VALUES(last_preview_at),
              updated_at = VALUES(updated_at)
            """;

    private static final String LEGACY_HEALTH_UPSERT = """
            INSERT INTO group_link_health (
              tenant_id, group_link_id, health_status, is_banned, current_count,
              last_check_at, health_failure_count, created_at, updated_at
            ) VALUES (?, ?, 1, 0, ?, ?, 0, ?, ?)
            ON DUPLICATE KEY UPDATE
              health_status = VALUES(health_status),
              is_banned = VALUES(is_banned),
              current_count = VALUES(current_count),
              last_check_at = VALUES(last_check_at),
              health_failure_count = VALUES(health_failure_count),
              updated_at = VALUES(updated_at)
            """;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("armada_lock_test")
            .withUsername("armada")
            .withPassword("armada")
            .withCommand(
                    "--transaction-isolation=REPEATABLE-READ",
                    "--innodb-deadlock-detect=ON",
                    "--innodb-lock-wait-timeout=5");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactionTemplate;
    private static AccountGroupMembershipMapper membershipMapper;
    private static AccountGroupMembershipSnapshotServiceImpl snapshotService;

    @BeforeAll
    static void configureProductionMappers() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);

        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);
        SqlSessionTemplate sqlSessionTemplate = buildSqlSessionTemplate(dataSource);
        membershipMapper = sqlSessionTemplate.getMapper(AccountGroupMembershipMapper.class);
        GroupLinkMapper groupLinkMapper = sqlSessionTemplate.getMapper(GroupLinkMapper.class);
        GroupLinkHealthMapper healthMapper = sqlSessionTemplate.getMapper(GroupLinkHealthMapper.class);
        GroupLinkRegistryServiceImpl registryService =
                new GroupLinkRegistryServiceImpl(groupLinkMapper, membershipMapper);
        snapshotService = new AccountGroupMembershipSnapshotServiceImpl(
                membershipMapper, groupLinkMapper, healthMapper, registryService);
    }

    @AfterAll
    static void clearTenantContext() {
        TenantContext.clear();
    }

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM account_group_membership");
        jdbc.update("DELETE FROM group_link_health");
        jdbc.update("DELETE FROM group_link_preview");
        jdbc.update("DELETE FROM group_link");
    }

    @Test
    void legacyInterleavedUpsertsReproduceSupremumDeadlock() throws Exception {
        seedLegacyPair();
        CyclicBarrier secondStatementStart = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<SQLException> first = executor.submit(() -> runLegacyCrossTableTransaction(
                    LEGACY_PREVIEW_UPSERT, 101L,
                    LEGACY_HEALTH_UPSERT, 101L,
                    secondStatementStart));
            Future<SQLException> second = executor.submit(() -> runLegacyCrossTableTransaction(
                    LEGACY_HEALTH_UPSERT, 102L,
                    LEGACY_PREVIEW_UPSERT, 102L,
                    secondStatementStart));

            List<SQLException> failures = java.util.stream.Stream.of(
                            first.get(15, TimeUnit.SECONDS),
                            second.get(15, TimeUnit.SECONDS))
                    .filter(java.util.Objects::nonNull)
                    .toList();

            assertThat(failures)
                    .as("旧的 preview/health 交叉 insert-on-duplicate 应由 InnoDB 检出死锁")
                    .anyMatch(error -> error.getErrorCode() == 1213
                            && "40001".equals(error.getSQLState()));
            assertThat(latestInnoDbDeadlock())
                    .contains("group_link_preview")
                    .contains("group_link_health")
                    .containsIgnoringCase("supremum");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void fourConcurrentCompleteSnapshotsWithMissingMembershipsFinishWithoutDeadlock() throws Exception {
        List<List<AccountGroupsReportedEvent.Group>> groupLists = seedCrossingGroupLists();

        try {
            assertThatCode(() -> runConcurrentSnapshots(groupLists, 10))
                    .doesNotThrowAnyException();
        } catch (AssertionError error) {
            throw new AssertionError(error.getMessage() + "\n" + latestInnoDbDeadlock(), error);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM group_link", Integer.class))
                .isEqualTo(THREAD_COUNT * (GROUPS_PER_THREAD + 1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM group_link_preview", Integer.class))
                .isEqualTo(THREAD_COUNT * GROUPS_PER_THREAD);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM group_link_health", Integer.class))
                .isEqualTo(THREAD_COUNT * GROUPS_PER_THREAD);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_group_membership", Integer.class))
                .isEqualTo(THREAD_COUNT * (GROUPS_PER_THREAD + 1));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM group_link_health WHERE current_count = 128",
                Integer.class)).isEqualTo(THREAD_COUNT * GROUPS_PER_THREAD);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_group_membership "
                        + "WHERE membership_status = 5 AND status_source = 'GROUP_SNAPSHOT'",
                Integer.class)).isEqualTo(THREAD_COUNT);
    }

    @Test
    void fourConcurrentFirstSeenOverlappingGroupListsCompleteWithoutDeadlock() throws Exception {
        List<List<AccountGroupsReportedEvent.Group>> groupLists = firstSeenOverlappingGroupLists();

        try {
            assertThatCode(() -> runConcurrentSnapshots(groupLists, 5))
                    .doesNotThrowAnyException();
        } catch (AssertionError error) {
            throw new AssertionError(error.getMessage() + "\n" + latestInnoDbDeadlock(), error);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM group_link", Integer.class))
                .isEqualTo(CROSSING_GROUP_POOL_SIZE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM group_link_preview", Integer.class))
                .isEqualTo(CROSSING_GROUP_POOL_SIZE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM group_link_health", Integer.class))
                .isEqualTo(CROSSING_GROUP_POOL_SIZE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM account_group_membership", Integer.class))
                .isEqualTo(THREAD_COUNT * GROUPS_PER_THREAD);
    }

    @Test
    void membershipUpdateFirstMatchesOriginalUpsertPrioritySemantics() {
        List<MembershipCase> cases = List.of(
                new MembershipCase(5, "LEGACY_MIGRATION", 100L, 50L, null, false, 200L, true),
                new MembershipCase(4, "WGP2_LEAVE", 300L, 50L, 250L, true, 200L, false),
                new MembershipCase(1, "WGP2_ADD", 200L, 50L, 180L, true, 200L, false),
                new MembershipCase(2, "OTHER", 200L, null, null, false, 200L, true));

        TenantContext.set(TENANT_ID);
        try {
            for (int index = 0; index < cases.size(); index++) {
                assertMembershipUpdateEquivalentToUpsert(index, cases.get(index));
            }
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void missingMembershipTargetedUpdateKeepsSnapshotPriorityAndTenantSemantics() {
        jdbc.update("""
                INSERT INTO account_group_membership (
                  tenant_id, account_id, group_link_id, group_jid, is_admin,
                  membership_status, status_source, status_updated_at,
                  joined_at, last_seen_at, created_at, updated_at
                ) VALUES
                  (7, 3000, 8000, 'old@g.us', 0, 1, 'GROUP_SNAPSHOT', 100, 1, 1, 1, 1),
                  (7, 3000, 8001, 'preserved@g.us', 0, 3, 'WGP2_REMOVE', 100, 1, 1, 1, 1),
                  (7, 3000, 8002, 'newer@g.us', 0, 1, 'GROUP_SNAPSHOT', 300, 1, 1, 1, 1),
                  (7, 3000, 8003, 'precise@g.us', 0, 1, 'WGP2_ADD', 200, 1, 1, 1, 1),
                  (7, 3000, 8004, 'equal@g.us', 0, 1, 'GROUP_SNAPSHOT', 200, 1, 1, 1, 1),
                  (7, 3000, 8005, 'visible@g.us', 0, 1, 'GROUP_SNAPSHOT', 100, 1, 1, 1, 1),
                  (8, 3000, 8006, 'other-tenant@g.us', 0, 1, 'GROUP_SNAPSHOT', 100, 1, 1, 1, 1)
                """);

        TenantContext.set(TENANT_ID);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                List<Long> ids = membershipMapper.selectMissingMembershipIds(
                        3_000L, List.of("visible@g.us"), List.of(3, 4), 200L);
                assertThat(ids).hasSize(2).isSorted();
                AccountGroupMembership row = new AccountGroupMembership();
                row.setMembershipStatus(5);
                row.setStatusSource("GROUP_SNAPSHOT");
                row.setStatusUpdatedAt(200L);
                row.setUpdatedAt(200L);
                assertThat(membershipMapper.markMembershipsNotInGroupByIds(
                        ids, row, List.of(3, 4))).isEqualTo(2);
            });
        } finally {
            TenantContext.clear();
        }

        assertThat(membershipStatus(TENANT_ID, "old@g.us")).isEqualTo(5);
        assertThat(membershipStatus(TENANT_ID, "equal@g.us")).isEqualTo(5);
        assertThat(membershipStatus(TENANT_ID, "preserved@g.us")).isEqualTo(3);
        assertThat(membershipStatus(TENANT_ID, "newer@g.us")).isEqualTo(1);
        assertThat(membershipStatus(TENANT_ID, "precise@g.us")).isEqualTo(1);
        assertThat(membershipStatus(TENANT_ID, "visible@g.us")).isEqualTo(1);
        assertThat(membershipStatus(8L, "other-tenant@g.us")).isEqualTo(1);
    }

    private static Integer membershipStatus(long tenantId, String groupJid) {
        return jdbc.queryForObject(
                "SELECT membership_status FROM account_group_membership "
                        + "WHERE tenant_id = ? AND group_jid = ?",
                Integer.class,
                tenantId,
                groupJid);
    }

    private static void assertMembershipUpdateEquivalentToUpsert(
            int index,
            MembershipCase testCase) {
        long controlAccountId = 2_000L + index * 2L;
        long updateAccountId = controlAccountId + 1L;
        String controlJid = "120363-membership-control-" + index + "@g.us";
        String updateJid = "120363-membership-update-" + index + "@g.us";
        seedMembership(controlAccountId, controlJid, testCase);
        seedMembership(updateAccountId, updateJid, testCase);

        transactionTemplate.executeWithoutResult(status -> {
            membershipMapper.upsertMembership(incomingMembership(
                    controlAccountId, controlJid, testCase.incomingAt(), testCase.incomingAdmin()));
            int affected = membershipMapper.updateActiveMembership(incomingMembership(
                    updateAccountId, updateJid, testCase.incomingAt(), testCase.incomingAdmin()));
            assertThat(affected).isEqualTo(1);
        });

        assertThat(readMembershipState(updateAccountId, updateJid))
                .isEqualTo(readMembershipState(controlAccountId, controlJid));
    }

    private static void seedMembership(
            long accountId,
            String groupJid,
            MembershipCase testCase) {
        jdbc.update("""
                INSERT INTO account_group_membership (
                  tenant_id, account_id, group_link_id, group_jid, is_admin,
                  membership_status, status_source, status_updated_at, joined_at,
                  last_seen_at, created_at, updated_at
                ) VALUES (?, ?, 8000, ?, ?, ?, ?, ?, ?, ?, 10, 10)
                """, TENANT_ID, accountId, groupJid, testCase.admin(),
                testCase.status(), testCase.source(), testCase.statusUpdatedAt(),
                testCase.joinedAt(), testCase.lastSeenAt());
    }

    private static AccountGroupMembership incomingMembership(
            long accountId,
            String groupJid,
            long incomingAt,
            boolean incomingAdmin) {
        AccountGroupMembership row = new AccountGroupMembership();
        row.setAccountId(accountId);
        row.setGroupLinkId(9_000L);
        row.setGroupJid(groupJid);
        row.setAdmin(incomingAdmin);
        row.setMembershipStatus(1);
        row.setStatusSource("GROUP_SNAPSHOT");
        row.setStatusUpdatedAt(incomingAt);
        row.setJoinedAt(incomingAt);
        row.setLastSeenAt(incomingAt);
        row.setCreatedAt(400L);
        row.setUpdatedAt(400L);
        return row;
    }

    private static MembershipState readMembershipState(long accountId, String groupJid) {
        return jdbc.queryForObject("""
                SELECT group_link_id, is_admin, membership_status, status_source,
                       status_updated_at, joined_at, last_seen_at, deleted_at, updated_at
                FROM account_group_membership
                WHERE tenant_id = ? AND account_id = ? AND group_jid = ?
                """, (resultSet, rowNumber) -> new MembershipState(
                resultSet.getLong("group_link_id"),
                (Boolean) resultSet.getObject("is_admin"),
                resultSet.getInt("membership_status"),
                resultSet.getString("status_source"),
                resultSet.getLong("status_updated_at"),
                nullableLong(resultSet, "joined_at"),
                nullableLong(resultSet, "last_seen_at"),
                nullableLong(resultSet, "deleted_at"),
                resultSet.getLong("updated_at")), TENANT_ID, accountId, groupJid);
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static void runConcurrentSnapshots(
            List<List<AccountGroupsReportedEvent.Group>> groupLists,
            int rounds) throws Exception {
        for (int round = 0; round < rounds; round++) {
            runConcurrentSnapshotRound(groupLists, round);
        }
    }

    private static void runConcurrentSnapshotRound(
            List<List<AccountGroupsReportedEvent.Group>> groupLists,
            int round) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (int threadIndex = 0; threadIndex < THREAD_COUNT; threadIndex++) {
                int currentThread = threadIndex;
                futures.add(executor.submit(() -> {
                    TenantContext.set(TENANT_ID);
                    try {
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("四线程群列表同步未在限定时间内开始");
                        }
                        Thread.sleep(currentThread * 3L);
                        long syncAt = 10_000L + round;
                        transactionTemplate.executeWithoutResult(status -> snapshotService.replaceVisibleGroups(
                                1_000L + currentThread,
                                groupLists.get(currentThread),
                                true,
                                syncAt,
                                "mysql-lock-round-" + round + "-thread-" + currentThread,
                                "testcontainers"));
                        return null;
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<Void> future : futures) {
                getFuture(future);
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static void getFuture(Future<Void> future) throws Exception {
        try {
            future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw error;
        }
    }

    private static List<List<AccountGroupsReportedEvent.Group>> seedCrossingGroupLists() {
        List<List<AccountGroupsReportedEvent.Group>> result = new ArrayList<>();
        long id = 1_000L;
        for (int thread = 0; thread < THREAD_COUNT; thread++) {
            List<AccountGroupsReportedEvent.Group> groups = new ArrayList<>();
            for (int groupIndex = 0; groupIndex < GROUPS_PER_THREAD; groupIndex++) {
                String jid = "120363-lock-" + thread + "-" + groupIndex + "@g.us";
                jdbc.update("""
                        INSERT INTO group_link (
                          id, tenant_id, link_url, group_name, origin, membership_state,
                          created_at, updated_at
                        ) VALUES (?, ?, ?, ?, 5, 2, 1, 1)
                        """, id, TENANT_ID, "wa://group/" + jid, "并发群" + groupIndex);
                jdbc.update("""
                        INSERT INTO group_link_preview (
                          tenant_id, group_link_id, group_jid, wa_subject, member_size,
                          last_preview_at, created_at, updated_at
                        ) VALUES (?, ?, ?, ?, 64, 1, 1, 1)
                        """, TENANT_ID, id, jid, "旧群名");
                jdbc.update("""
                        INSERT INTO group_link_health (
                          tenant_id, group_link_id, health_status, is_banned, current_count,
                          last_check_at, health_failure_count, created_at, updated_at
                        ) VALUES (?, ?, 1, 0, 64, 1, 0, 1, 1)
                        """, TENANT_ID, id);
                groups.add(new AccountGroupsReportedEvent.Group(
                        jid, "新群名" + groupIndex, 128, null, null, false, false, null));
                id++;
            }
            String missingJid = "120363-missing-" + thread + "@g.us";
            jdbc.update("""
                    INSERT INTO group_link (
                      id, tenant_id, link_url, group_name, origin, membership_state,
                      created_at, updated_at
                    ) VALUES (?, ?, ?, '历史缺失群', 5, 2, 1, 1)
                    """, id, TENANT_ID, "wa://group/" + missingJid);
            jdbc.update("""
                    INSERT INTO account_group_membership (
                      tenant_id, account_id, group_link_id, group_jid, is_admin,
                      membership_status, status_source, status_updated_at,
                      joined_at, last_seen_at, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, 0, 1, 'GROUP_SNAPSHOT', 1, 1, 1, 1, 1)
                    """, TENANT_ID, 1_000L + thread, id, missingJid);
            id++;
            result.add(List.copyOf(groups));
        }
        return List.copyOf(result);
    }

    private static List<List<AccountGroupsReportedEvent.Group>> firstSeenOverlappingGroupLists() {
        List<AccountGroupsReportedEvent.Group> pool = new ArrayList<>();
        for (int groupIndex = 0; groupIndex < CROSSING_GROUP_POOL_SIZE; groupIndex++) {
            pool.add(new AccountGroupsReportedEvent.Group(
                    "120363-first-seen-" + groupIndex + "@g.us",
                    "首次交叉群" + groupIndex,
                    96,
                    null,
                    null,
                    false,
                    false,
                    null));
        }
        List<List<AccountGroupsReportedEvent.Group>> result = new ArrayList<>();
        for (int thread = 0; thread < THREAD_COUNT; thread++) {
            List<AccountGroupsReportedEvent.Group> groups = new ArrayList<>();
            int start = thread * (GROUPS_PER_THREAD / 2);
            for (int offset = 0; offset < GROUPS_PER_THREAD; offset++) {
                groups.add(pool.get((start + offset) % CROSSING_GROUP_POOL_SIZE));
            }
            if ((thread & 1) == 1) {
                java.util.Collections.reverse(groups);
            }
            result.add(List.copyOf(groups));
        }
        return List.copyOf(result);
    }

    private static void seedLegacyPair() {
        for (long groupLinkId : List.of(101L, 102L)) {
            String jid = "120363-legacy-" + groupLinkId + "@g.us";
            jdbc.update("""
                    INSERT INTO group_link (
                      id, tenant_id, link_url, group_name, origin, membership_state,
                      created_at, updated_at
                    ) VALUES (?, ?, ?, 'legacy', 5, 2, 1, 1)
                    """, groupLinkId, TENANT_ID, "wa://group/" + jid);
            jdbc.update("""
                    INSERT INTO group_link_preview (
                      tenant_id, group_link_id, group_jid, wa_subject, member_size,
                      last_preview_at, created_at, updated_at
                    ) VALUES (?, ?, ?, 'legacy', 1, 1, 1, 1)
                    """, TENANT_ID, groupLinkId, jid);
            jdbc.update("""
                    INSERT INTO group_link_health (
                      tenant_id, group_link_id, health_status, is_banned, current_count,
                      last_check_at, health_failure_count, created_at, updated_at
                    ) VALUES (?, ?, 1, 0, 1, 1, 0, 1, 1)
                    """, TENANT_ID, groupLinkId);
        }
    }

    private static SQLException runLegacyCrossTableTransaction(
            String firstSql,
            long firstGroupLinkId,
            String secondSql,
            long secondGroupLinkId,
            CyclicBarrier secondStatementStart) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                executeLegacyUpsert(connection, firstSql, firstGroupLinkId);
                secondStatementStart.await(5, TimeUnit.SECONDS);
                executeLegacyUpsert(connection, secondSql, secondGroupLinkId);
                connection.commit();
                return null;
            } catch (SQLException error) {
                connection.rollback();
                return error;
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private static void executeLegacyUpsert(
            Connection connection,
            String sql,
            long groupLinkId) throws SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, TENANT_ID);
            statement.setLong(2, groupLinkId);
            if (LEGACY_PREVIEW_UPSERT.equals(sql)) {
                statement.setString(3, "120363-legacy-" + groupLinkId + "@g.us");
                statement.setString(4, "legacy-updated");
                statement.setInt(5, 2);
                statement.setLong(6, now);
                statement.setLong(7, now);
                statement.setLong(8, now);
            } else {
                statement.setInt(3, 2);
                statement.setLong(4, now);
                statement.setLong(5, now);
                statement.setLong(6, now);
            }
            statement.executeUpdate();
        }
    }

    private static String latestInnoDbDeadlock() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SHOW ENGINE INNODB STATUS")) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString("Status");
        }
    }

    private static SqlSessionTemplate buildSqlSessionTemplate(DataSource dataSource) throws Exception {
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
                new ClassPathResource("mapper/group/AccountGroupMembershipMapper.xml"),
                new ClassPathResource("mapper/group/GroupLinkMapper.xml"),
                new ClassPathResource("mapper/group/GroupLinkHealthMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) {
            throw new IllegalStateException("MySQL 并发测试无法创建 SqlSessionFactory");
        }
        return new SqlSessionTemplate(factory);
    }

    private static void createSchema(JdbcTemplate template) {
        template.execute("""
                CREATE TABLE group_link (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  tenant_id BIGINT NOT NULL,
                  link_url VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
                  group_name VARCHAR(128) DEFAULT NULL,
                  label_id BIGINT DEFAULT NULL,
                  import_batch_id BIGINT DEFAULT NULL,
                  origin TINYINT NOT NULL DEFAULT 1,
                  membership_state TINYINT NOT NULL DEFAULT 1,
                  remark VARCHAR(255) DEFAULT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  created_by BIGINT DEFAULT NULL,
                  deleted_at BIGINT DEFAULT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uq_url (tenant_id, link_url)
                ) ENGINE=InnoDB
                """);
        template.execute("""
                CREATE TABLE group_link_preview (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  tenant_id BIGINT NOT NULL,
                  group_link_id BIGINT NOT NULL,
                  group_jid VARCHAR(64) DEFAULT NULL,
                  invite_code VARCHAR(64) DEFAULT NULL,
                  wa_subject VARCHAR(255) DEFAULT NULL,
                  member_size INT DEFAULT NULL,
                  owner_phone VARCHAR(32) DEFAULT NULL,
                  announce_only TINYINT(1) DEFAULT NULL,
                  avatar_url VARCHAR(512) DEFAULT NULL,
                  last_preview_at BIGINT DEFAULT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uq_group_link_preview_link (tenant_id, group_link_id),
                  KEY idx_group_link_preview_jid (tenant_id, group_jid)
                ) ENGINE=InnoDB
                """);
        template.execute("""
                CREATE TABLE group_link_health (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  tenant_id BIGINT NOT NULL,
                  group_link_id BIGINT NOT NULL,
                  health_status TINYINT DEFAULT NULL,
                  is_banned TINYINT(1) DEFAULT NULL,
                  current_count INT DEFAULT NULL,
                  last_check_at BIGINT DEFAULT NULL,
                  last_health_error VARCHAR(64) DEFAULT NULL,
                  health_failure_count INT NOT NULL DEFAULT 0,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uq_group_link_health_link (tenant_id, group_link_id)
                ) ENGINE=InnoDB
                """);
        template.execute("""
                CREATE TABLE account_group_membership (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL,
                  group_link_id BIGINT NOT NULL,
                  group_jid VARCHAR(128) NOT NULL,
                  is_admin TINYINT(1) DEFAULT NULL,
                  membership_status TINYINT NOT NULL DEFAULT 1,
                  status_source VARCHAR(64) DEFAULT NULL,
                  status_updated_at BIGINT NOT NULL,
                  joined_at BIGINT DEFAULT NULL,
                  last_seen_at BIGINT DEFAULT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  deleted_at BIGINT DEFAULT NULL,
                  is_active TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) VIRTUAL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uq_account_group_membership
                    (tenant_id, account_id, group_jid, is_active),
                  KEY idx_account_group_membership_account
                    (tenant_id, account_id, deleted_at),
                  KEY idx_account_group_membership_group_link
                    (tenant_id, group_link_id, deleted_at),
                  KEY idx_account_group_membership_jid
                    (tenant_id, group_jid),
                  KEY idx_account_group_membership_status
                    (tenant_id, account_id, membership_status, deleted_at),
                  KEY idx_account_group_membership_account_joined
                    (tenant_id, account_id, deleted_at, joined_at)
                ) ENGINE=InnoDB
                """);
    }

    private record MembershipCase(
            int status,
            String source,
            long statusUpdatedAt,
            Long joinedAt,
            Long lastSeenAt,
            boolean admin,
            long incomingAt,
            boolean incomingAdmin) {
    }

    private record MembershipState(
            long groupLinkId,
            Boolean admin,
            int status,
            String source,
            long statusUpdatedAt,
            Long joinedAt,
            Long lastSeenAt,
            Long deletedAt,
            long updatedAt) {
    }
}
