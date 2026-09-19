package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ControlledObservation;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ControlledWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ParticipantPresenceWrite;
import com.armada.group.service.impl.GroupCurrentSnapshotMySqlTestSupport.RecordingDataSource;
import com.armada.shared.tenant.TenantContext;
import com.armada.platform.protocol.model.result.GroupParticipantResult;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.LongStream;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.SqlSessionFactory;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 临时 MySQL 8.4 验证整条受控成员批处理、PN 索引及 InnoDB 当前读行锁。 */
@Testcontainers
class AccountGroupControlledBatchMySqlTest {

    private static final Logger LOG = LoggerFactory.getLogger(AccountGroupControlledBatchMySqlTest.class);
    private static final long TENANT_ID = 7L;
    private static final long GROUP_ID = 101L;
    private static final String GROUP_JID = "120363-controlled-batch@g.us";
    private static final long OBSERVED_AT = 1_789_363_137_123L;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("armada_controlled_batch")
            .withUsername("armada").withPassword("armada")
            .withCommand("--transaction-isolation=REPEATABLE-READ", "--innodb-lock-wait-timeout=5");

    private static DriverManagerDataSource rawDataSource;
    private static RecordingDataSource recording;
    private static JdbcTemplate jdbc;
    private static JdbcTemplate transactionalJdbc;
    private static TransactionTemplate tx;
    private static SqlSessionFactory sessionFactory;
    private static AccountGroupCurrentSnapshotMapper mapper;
    private static AccountGroupCurrentSnapshotPersistenceImpl persistence;

    @BeforeAll
    static void setUpProductionMapper() throws Exception {
        rawDataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        rawDataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        jdbc = new JdbcTemplate(rawDataSource);
        GroupCurrentSnapshotMySqlTestSupport.createLegacyContextSchema(jdbc);
        GroupCurrentSnapshotMySqlTestSupport.executeV120(rawDataSource);
        GroupCurrentSnapshotMySqlTestSupport.executeV139(rawDataSource);
        jdbc.execute("CREATE TABLE group_link (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, "
                + "group_id BIGINT, link_url VARCHAR(512), updated_at BIGINT, deleted_at BIGINT) ENGINE=InnoDB");
        recording = new RecordingDataSource(rawDataSource);
        transactionalJdbc = new JdbcTemplate(recording);
        tx = new TransactionTemplate(new DataSourceTransactionManager(recording));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MyBatisConfig production = new MyBatisConfig();
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(recording);
        factory.setConfiguration(configuration);
        factory.setPlugins(production.mybatisPlusInterceptor(production.tenantLineHandler()));
        factory.setMapperLocations(new ClassPathResource("mapper/group/AccountGroupCurrentSnapshotMapper.xml"));
        sessionFactory = factory.getObject();
        mapper = new SqlSessionTemplate(sessionFactory).getMapper(AccountGroupCurrentSnapshotMapper.class);
        persistence = new AccountGroupCurrentSnapshotPersistenceImpl(mapper);
    }

    @BeforeEach
    void resetData() {
        for (String table : List.of("wa_account_group_binding", "wa_group_participant", "wa_group_profile", "wa_group",
                "account_group_sync_state", "account_group_baseline", "account", "group_link")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("INSERT INTO wa_group (id,tenant_id,group_jid,origin,created_at,updated_at) "
                + "VALUES (?,7,?,5,100,100)", GROUP_ID, GROUP_JID);
        for (long id = 1; id <= 15; id++) {
            jdbc.update("INSERT INTO account (id,tenant_id,ws_phone,protocol_id,protocol_account_id,"
                    + "created_at,updated_at) VALUES (?,7,?,'ANDROID',?,100,100)", id, phone(id), "account-" + id);
        }
        TenantContext.set(TENANT_ID);
        recording.reset();
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void oneProfileWritesItsParticipantsOnlyOnce() {
        List<GroupParticipantResult> members = LongStream.rangeClosed(1, 15)
                .mapToObj(id -> new GroupParticipantResult(phone(id) + "@s.whatsapp.net",
                        phone(id) + "@s.whatsapp.net", phone(id), id % 2 == 1, false, null))
                .toList();
        tx.executeWithoutResult(status -> {
            var group = persistence.resolveGroupWriteContext(null, GROUP_JID);
            persistence.fillGroupCreatedAt(group, 100L);
            var written = persistence.replaceCompleteParticipantSnapshot(group, members, OBSERVED_AT, "profile-once");
            persistence.reconcileProfileSnapshotBindings(group, observations(15, OBSERVED_AT), written);
        });
        assertThat(recording.statements().stream()
                .filter(sql -> sql.startsWith("INSERT INTO WA_GROUP_PARTICIPANT ")))
                .as("同一条完整资料的成员事实不应在账号关联阶段再写一遍").hasSize(1);
        assertAllFifteenBindingsAndRoles();
        int consolidatedCount = recording.statements().size();
        assertThat(recording.statements().stream().filter(sql ->
                sql.contains("FROM WA_GROUP FORCE INDEX (PRIMARY)")))
                .as("同事务只取一次群主键写锁").hasSize(1);
        resetData();
        // 按上一版的调用顺序保留每一步重新解析群及绑定阶段成员补写，作为 SQL 对照。
        tx.executeWithoutResult(status -> {
            persistence.resolveGroupWriteContext(null, GROUP_JID);
            persistence.fillGroupCreatedAt(persistence.resolveGroupWriteContext(null, GROUP_JID), 100L);
            persistence.replaceCompleteParticipantSnapshot(
                    persistence.resolveGroupWriteContext(null, GROUP_JID), members, OBSERVED_AT, "profile-once");
            persistence.applyControlledParticipantObservations(GROUP_JID, observations(15, OBSERVED_AT));
        });
        int repeatedCount = recording.statements().size();
        assertThat(recording.statements().stream()
                .filter(sql -> sql.startsWith("INSERT INTO WA_GROUP_PARTICIPANT "))).hasSize(2);
        assertAllFifteenBindingsAndRoles();
        assertThat(consolidatedCount).isLessThan(repeatedCount);
        LOG.info("profile persistence SQL: repeated={}, consolidated={}", repeatedCount, consolidatedCount);
    }

    @Test
    void wholeServiceBatchUsesBoundedStatementsAndPreservesAllFifteenBindingsAndRoles() {
        List<ControlledObservation> one = observations(1, OBSERVED_AT);
        List<ControlledObservation> fifteen = observations(15, OBSERVED_AT + 1);
        recording.reset();
        tx.executeWithoutResult(status -> persistence.applyControlledParticipantObservations(GROUP_JID, one));
        int oneCount = recording.statements().size();
        recording.reset();
        tx.executeWithoutResult(status -> persistence.applyControlledParticipantObservations(GROUP_JID, fifteen));
        int batchCount = recording.statements().size();
        assertAllFifteenBindingsAndRoles();
        recording.reset();
        tx.executeWithoutResult(status -> observations(15, OBSERVED_AT + 2).forEach(row ->
                persistence.applyControlledParticipantObservation(row.accountId(), GROUP_JID,
                        row.inGroup(), row.admin(), row.observedAt(), row.eventId(), row.source())));
        int individualCount = recording.statements().size();

        LOG.info("controlled batch SQL: one={}, fifteen={}, old-fifteen={}", oneCount, batchCount, individualCount);
        assertThat(batchCount).isEqualTo(oneCount).isLessThanOrEqualTo(10);
        assertThat(individualCount).isGreaterThanOrEqualTo(batchCount * 10);
    }

    private void assertAllFifteenBindingsAndRoles() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wa_account_group_binding", Integer.class)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wa_group_participant", Integer.class)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wa_account_group_binding b "
                + "JOIN wa_group_participant p ON p.id=b.participant_id AND p.tenant_id=b.tenant_id "
                + "WHERE p.pn_jid=CONCAT('155500000',LPAD(b.account_id,2,'0'),'@s.whatsapp.net') "
                + "AND p.presence_status=1 AND p.role=IF(MOD(b.account_id,2)=1,2,1)", Integer.class))
                .isEqualTo(15);
    }

    @Test
    void participantJoinsUseTheCompleteTenantGroupPnIndex() throws Exception {
        tx.executeWithoutResult(status -> persistence.applyControlledParticipantObservations(
                GROUP_JID, observations(15, OBSERVED_AT)));
        List<Object[]> unrelated = new ArrayList<>();
        List<Object[]> unrelatedAccounts = new ArrayList<>();
        for (int id = 0; id < 3_000; id++) {
            unrelated.add(new Object[]{"1888000" + id + "@s.whatsapp.net"});
            unrelatedAccounts.add(new Object[]{10_000L + id, "1888000" + id});
        }
        jdbc.batchUpdate("INSERT INTO account (id,tenant_id,ws_phone,created_at,updated_at) "
                + "VALUES (?,7,?,100,100)", unrelatedAccounts);
        jdbc.batchUpdate("INSERT INTO wa_group_participant (tenant_id,group_id,pn_jid,created_at,updated_at) "
                + "VALUES (7,101,?,100,100)", unrelated);
        jdbc.update("INSERT INTO wa_account_group_binding "
                + "(tenant_id,account_id,group_id,participant_id,created_at,updated_at) "
                + "SELECT 7,a.id,101,p.id,100,100 FROM account a "
                + "JOIN wa_group_participant p ON p.tenant_id=7 AND p.group_id=101 "
                + "AND p.pn_jid=CONVERT(CONCAT(a.ws_phone,'@s.whatsapp.net') USING ascii) "
                + "WHERE a.tenant_id=7 AND a.id>=10000");
        jdbc.execute("ANALYZE TABLE account, wa_group_participant, wa_account_group_binding");
        Map<String, Object> parameters = Map.of("tenantId", TENANT_ID, "groupId", GROUP_ID, "rows", writes(15));
        SoftAssertions assertions = new SoftAssertions();
        assertFullPnLookup("selectControlledExistingAfterGroupLock", "p", parameters, assertions);
        assertFullPnLookup("upsertControlledBindings", "participant", parameters, assertions);
        assertions.assertAll();
    }

    @Test
    void lockedBatchReadBlocksParticipantWriteUntilTheTransactionCommits() throws Exception {
        tx.executeWithoutResult(status -> persistence.applyControlledParticipantObservations(
                GROUP_JID, observations(1, OBSERVED_AT)));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<Integer>> pending = new AtomicReference<>();
        CountDownLatch attempted = new CountDownLatch(1);
        try {
            tx.executeWithoutResult(status -> {
                mapper.selectGroupIdsByIds(TENANT_ID, List.of(GROUP_ID));
                mapper.selectControlledExistingAfterGroupLock(TENANT_ID, GROUP_ID, writes(1));
                pending.set(executor.submit(() -> tx.execute(other -> {
                    attempted.countDown();
                    return transactionalJdbc.update("UPDATE wa_group_participant SET role=1 "
                            + "WHERE tenant_id=7 AND group_id=101 AND pn_jid=?", phone(1) + "@s.whatsapp.net");
                })));
                assertThatThrownBy(() -> {
                    assertThat(attempted.await(2, TimeUnit.SECONDS)).isTrue();
                    pending.get().get(250, TimeUnit.MILLISECONDS);
                }).isInstanceOf(TimeoutException.class);
            });
            assertThat(pending.get().get(2, TimeUnit.SECONDS)).isOne();
            assertThat(jdbc.queryForObject("SELECT role FROM wa_group_participant", Integer.class)).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void lateExitKeepsRejoinedCycleAndRollbackKeepsAllBindingsAndIdentity() {
        tx.executeWithoutResult(status -> persistence.applyControlledParticipantObservations(GROUP_JID,
                List.of(new ControlledObservation(1L, true, true, OBSERVED_AT, "join", "WGP2_ADD"))));
        jdbc.update("UPDATE wa_group_participant SET lid_jid='112233@lid' WHERE tenant_id=7");
        tx.executeWithoutResult(status -> persistence.applyControlledParticipantObservations(GROUP_JID,
                List.of(new ControlledObservation(1L, false, false, OBSERVED_AT + 1, "exit", "WGP2_LEAVE"))));
        tx.executeWithoutResult(status -> persistence.applyControlledParticipantObservations(GROUP_JID,
                List.of(new ControlledObservation(1L, true, false, OBSERVED_AT + 2, "rejoin", "WGP2_ADD"))));
        tx.executeWithoutResult(status -> persistence.applyControlledParticipantObservations(GROUP_JID,
                List.of(new ControlledObservation(1L, false, false, OBSERVED_AT + 1, "late-exit", "WGP2_LEAVE"))));
        assertThat(jdbc.queryForObject("SELECT membership_active_since_at FROM wa_account_group_binding", Long.class))
                .isEqualTo(OBSERVED_AT + 2);
        assertThat(jdbc.queryForObject("SELECT lid_jid FROM wa_group_participant", String.class)).isEqualTo("112233@lid");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            persistence.applyControlledParticipantObservations(GROUP_JID, observations(15, OBSERVED_AT + 3));
            throw new IllegalStateException("rollback controlled batch");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wa_account_group_binding", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wa_group_participant", Integer.class)).isOne();
    }

    private void assertFullPnLookup(String method, String participantAlias,
                                   Map<String, Object> parameters, SoftAssertions assertions)
            throws Exception {
        MappedStatement statement = sessionFactory.getConfiguration().getMappedStatement(
                AccountGroupCurrentSnapshotMapper.class.getName() + "." + method);
        BoundSql bound = statement.getBoundSql(parameters);
        String sql = bound.getSql();
        if (method.equals("upsertControlledBindings")) {
            sql = sql.substring(sql.indexOf("SELECT"), sql.indexOf("ON DUPLICATE KEY UPDATE"));
        }
        try (Connection connection = rawDataSource.getConnection();
             PreparedStatement explain = connection.prepareStatement("EXPLAIN FORMAT=JSON " + sql)) {
            new DefaultParameterHandler(statement, parameters, bound).setParameters(explain);
            try (ResultSet result = explain.executeQuery()) {
                assertThat(result.next()).isTrue();
                String plan = result.getString(1);
                LOG.info("controlled batch {} EXPLAIN: {}", method, plan);
                JsonNode participant = new ObjectMapper().readTree(plan).findValues("table").stream()
                        .filter(node -> node.path("table_name").asText().equals(participantAlias))
                        .findFirst().orElseThrow();
                assertions.assertThat(participant.path("key").asText()).as(method + " PN index")
                        .isEqualTo("uq_wa_group_participant_pn");
                assertions.assertThat(participant.path("used_key_parts").toString()).as(method + " full PN key")
                        .contains("tenant_id", "group_id", "pn_jid");
                assertions.assertThat(participant.toString()).as(method + " unconverted indexed PN")
                        .doesNotContain("convert(`" + participantAlias + "`.`pn_jid`");
                if (method.equals("selectControlledExistingAfterGroupLock")) {
                    JsonNode binding = new ObjectMapper().readTree(plan).findValues("table").stream()
                            .filter(node -> node.path("table_name").asText().equals("b"))
                            .findFirst().orElseThrow();
                    assertions.assertThat(binding.path("key").asText())
                            .isEqualTo("uq_wa_account_group_binding");
                    assertions.assertThat(binding.path("used_key_parts").toString())
                            .contains("tenant_id", "account_id", "group_id");
                }
            }
        }
    }

    @Test
    void newerProfilesKeepEveryAccountBindingAndUpdateRolesIncludingOwner() {
        applyProfile(List.of(member(1, false, false), member(2, false, false)),
                OBSERVED_AT, "account-one-report");
        applyProfile(List.of(member(1, true, true), member(2, true, false)),
                OBSERVED_AT + 10, "account-two-report");
        assertParticipant(1, 1, 3, OBSERVED_AT + 10);
        assertParticipant(2, 1, 2, OBSERVED_AT + 10);
        assertThat(jdbc.queryForList("SELECT account_id FROM wa_account_group_binding ORDER BY account_id",
                Long.class)).containsExactly(1L, 2L);
        applyProfile(List.of(member(1, false, false), member(2, false, false)),
                OBSERVED_AT + 20, "account-one-next-report");
        assertParticipant(1, 1, 1, OBSERVED_AT + 20);
        assertParticipant(2, 1, 1, OBSERVED_AT + 20);
    }

    @Test
    void rejectedOldSnapshotStillRepairsMissingControlledMemberWithoutRegressingNewerFacts() {
        applyProfile(List.of(member(1, true, true)), OBSERVED_AT + 20, "newer-profile");
        Set<String> written = applyProfile(List.of(member(1, false, false), member(2, true, false)),
                OBSERVED_AT + 10, "older-profile");
        assertThat(written).isEmpty();
        assertParticipant(1, 1, 3, OBSERVED_AT + 20);
        assertParticipant(2, 1, 2, OBSERVED_AT + 10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wa_account_group_binding", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT member_snapshot_version FROM wa_group_profile", String.class))
                .isEqualTo("newer-profile");
    }

    @Test
    void preciseExitAndRejoinCycleSurviveInterleavedProfileReports() {
        applyControlled(1, true, false, OBSERVED_AT, "WGP2_ADD");
        applyProfile(List.of(member(1, false, false)), OBSERVED_AT + 10, "before-exit");
        applyControlled(1, false, false, OBSERVED_AT + 30, "WGP2_REMOVE");
        applyProfile(List.of(member(1, false, false)), OBSERVED_AT + 20, "delayed-before-exit");
        assertParticipant(1, 2, 1, OBSERVED_AT + 30);
        assertThat(jdbc.queryForObject("SELECT membership_active_since_at FROM wa_account_group_binding",
                Long.class)).isNull();
        applyControlled(1, true, true, OBSERVED_AT + 40, "WGP2_ADD");
        applyProfile(List.of(member(1, false, false)), OBSERVED_AT + 50, "after-rejoin");
        assertParticipant(1, 1, 2, OBSERVED_AT + 50);
        assertThat(jdbc.queryForObject("SELECT membership_active_since_at FROM wa_account_group_binding",
                Long.class)).isEqualTo(OBSERVED_AT + 40);
        applyControlled(1, true, false, OBSERVED_AT + 60, "WGP2_DEMOTE");
        applyProfile(List.of(member(1, true, false)), OBSERVED_AT + 70, "after-demote");
        assertParticipant(1, 1, 1, OBSERVED_AT + 70);
    }

    @Test
    void sameTimeSnapshotVersionAndEmptyCompleteListKeepTheirExistingMeaning() {
        applyProfile(List.of(member(1, false, false)), OBSERVED_AT, "snapshot-a");
        applyProfile(List.of(member(1, true, true)), OBSERVED_AT, "snapshot-z");
        applyProfile(List.of(member(1, false, false)), OBSERVED_AT, "snapshot-b");
        assertParticipant(1, 1, 3, OBSERVED_AT);
        applyProfile(List.of(), OBSERVED_AT + 1, "empty-complete");
        assertParticipant(1, 2, 3, OBSERVED_AT + 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wa_account_group_binding", Integer.class)).isEqualTo(1);
    }

    @Test
    void linkedPnAndLidStillMergeBeforeControlledBindingIsWritten() {
        applyControlled(1, true, false, OBSERVED_AT, "WGP2_ADD");
        String lid = "123456789012345@lid";
        jdbc.update("INSERT INTO wa_group_participant (tenant_id,group_id,lid_jid,created_at,updated_at) "
                + "VALUES (7,101,?,100,100)", lid);
        Set<String> written = applyProfile(List.of(new GroupParticipantResult(
                lid, phone(1) + "@s.whatsapp.net", phone(1), true, true, "superadmin")),
                OBSERVED_AT + 10, "linked-identities");
        assertThat(written).containsExactly(phone(1) + "@s.whatsapp.net");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wa_group_participant", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT p.pn_jid,p.lid_jid,b.account_id FROM wa_group_participant p "
                + "JOIN wa_account_group_binding b ON b.participant_id=p.id"))
                .containsEntry("pn_jid", phone(1) + "@s.whatsapp.net")
                .containsEntry("lid_jid", lid).containsEntry("account_id", 1L);
    }

    @Test
    void profileAndBindingsRollBackTogether() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            applyProfile(List.of(member(1, true, true)), OBSERVED_AT, "rollback-profile");
            throw new IllegalStateException("failure after binding");
        })).isInstanceOf(IllegalStateException.class);
        for (String table : List.of("wa_group_profile", "wa_group_participant", "wa_account_group_binding")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class))
                    .as(table).isZero();
        }
    }

    @Test
    void lockedContextCannotBeReusedUnderAnotherTenant() {
        tx.executeWithoutResult(status -> {
            var group = persistence.resolveGroupWriteContext(null, GROUP_JID);
            recording.reset();
            TenantContext.set(8L);
            try {
                assertThatThrownBy(() -> persistence.fillGroupCreatedAt(group, 100L))
                        .hasMessageContaining("当前租户");
                assertThatThrownBy(() -> persistence.replaceCompleteParticipantSnapshot(
                        group, List.of(member(1, true, true)), OBSERVED_AT, "wrong-tenant"))
                        .hasMessageContaining("当前租户");
                assertThatThrownBy(() -> persistence.reconcileProfileSnapshotBindings(
                        group, observations(1, OBSERVED_AT), Set.of()))
                        .hasMessageContaining("当前租户");
                assertThat(recording.statements()).isEmpty();
            } finally {
                TenantContext.set(TENANT_ID);
            }
        });
    }

    private Set<String> applyProfile(List<GroupParticipantResult> members, long at, String eventId) {
        return tx.execute(status -> {
            var group = persistence.resolveGroupWriteContext(null, GROUP_JID);
            var written = persistence.replaceCompleteParticipantSnapshot(group, members, at, eventId);
            List<ControlledObservation> accounts = members.stream().map(member -> {
                long accountId = Long.parseLong(member.phone().substring(member.phone().length() - 2));
                return new ControlledObservation(accountId, true,
                        Boolean.TRUE.equals(member.admin()) || Boolean.TRUE.equals(member.owner()),
                        at, eventId, "FULL_SNAPSHOT");
            }).toList();
            persistence.reconcileProfileSnapshotBindings(group, accounts, written);
            return written;
        });
    }

    private void applyControlled(long accountId, boolean inGroup, boolean admin, long at, String source) {
        tx.executeWithoutResult(status -> persistence.applyControlledParticipantObservations(GROUP_JID,
                List.of(new ControlledObservation(accountId, inGroup, admin, at, source + at, source))));
    }

    private static GroupParticipantResult member(long id, boolean admin, boolean owner) {
        return new GroupParticipantResult(phone(id) + "@s.whatsapp.net",
                phone(id) + "@s.whatsapp.net", phone(id), admin, owner, null);
    }

    private void assertParticipant(long accountId, int presence, int role, long observedAt) {
        assertThat(jdbc.queryForMap("SELECT presence_status,role,presence_observed_at "
                + "FROM wa_group_participant WHERE pn_jid=?", phone(accountId) + "@s.whatsapp.net"))
                .containsEntry("presence_status", presence).containsEntry("role", role)
                .containsEntry("presence_observed_at", observedAt);
    }

    private static List<ControlledObservation> observations(int size, long at) {
        return LongStream.rangeClosed(1, size).mapToObj(id -> new ControlledObservation(
                id, true, id % 2 == 1, at, "profile-" + at, "FULL_SNAPSHOT")).toList();
    }

    private static List<ControlledWrite> writes(int size) {
        return LongStream.rangeClosed(1, size).mapToObj(id -> new ControlledWrite(id,
                new ParticipantPresenceWrite(GROUP_ID, GROUP_JID, phone(id) + "@s.whatsapp.net",
                        null, phone(id), 1, "FULL_SNAPSHOT", "explain", OBSERVED_AT, OBSERVED_AT,
                        null, null, null, null, 1, "FULL_SNAPSHOT", OBSERVED_AT,
                        "explain", null, null, null, null, null))).toList();
    }

    private static String phone(long id) {
        return "155500000%02d".formatted(id);
    }
}
