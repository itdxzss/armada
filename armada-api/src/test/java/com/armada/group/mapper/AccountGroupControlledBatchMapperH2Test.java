package com.armada.group.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.Context;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ControlledExisting;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ControlledWrite;
import com.armada.group.model.dto.AccountGroupCurrentSnapshotRows.ParticipantPresenceWrite;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 批量受控账号群关系使用真实 Mapper XML、租户插件及 Spring 事务验证。 */
class AccountGroupControlledBatchMapperH2Test {

    private static final long TENANT_ID = 7L;
    private static final long GROUP_ID = 101L;
    private static final String GROUP_JID = "batch-group@g.us";
    private JdbcTemplate jdbc;
    private AccountGroupCurrentSnapshotMapper mapper;
    private TransactionTemplate tx;
    private StatementCounter statementCounter;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:controlled_batch_mapper;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP ALL OBJECTS");
        createSchema();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        MyBatisConfig production = new MyBatisConfig();
        statementCounter = new StatementCounter();
        factory.setPlugins(production.mybatisPlusInterceptor(production.tenantLineHandler()), statementCounter);
        factory.setMapperLocations(h2MapperResource());
        mapper = new SqlSessionTemplate(factory.getObject())
                .getMapper(AccountGroupCurrentSnapshotMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        TenantContext.set(TENANT_ID);
        jdbc.update("INSERT INTO wa_group VALUES (?, ?, ?, NULL)", GROUP_ID, TENANT_ID, GROUP_JID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private ByteArrayResource h2MapperResource() throws Exception {
        String xml;
        try (var input = new ClassPathResource("mapper/group/AccountGroupCurrentSnapshotMapper.xml")
                .getInputStream()) {
            xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String pnCast = "CAST(#{item.row.pnJid} AS CHAR(191) CHARACTER SET ascii) COLLATE ascii_bin";
        assertThat(xml.split(Pattern.quote(pnCast), -1).length - 1).isEqualTo(2);
        // H2 不解析 MySQL 字符集修饰；只适配该修饰。原 XML 的 ASCII 索引及行锁由 MySqlTest 真跑。
        return new ByteArrayResource(xml.replace(" CHARACTER SET ascii", "")
                .replace(" COLLATE ascii_bin", "").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void fifteenAccountsReadContextsAndWriteOwnBindingsWithoutLosingIdentity() {
        List<ControlledWrite> rows = new ArrayList<>();
        for (long accountId = 1; accountId <= 15; accountId++) {
            insertAccount(accountId, TENANT_ID);
            insertParticipant(accountId, TENANT_ID, GROUP_ID, 1, "FULL_SNAPSHOT", 200);
            rows.add(write(accountId, 1, "FULL_SNAPSHOT", 200, 200L));
        }
        jdbc.update("INSERT INTO account_group_sync_state "
                + "(tenant_id,account_id,baseline_state,baseline_completeness,baseline_group_count) "
                + "VALUES (7,1,1,1,0)");
        List<Long> ids = LongStream.rangeClosed(1, 15).boxed().toList();

        assertThat(mapper.selectContexts(TENANT_ID, ids)).extracting(Context::accountId)
                .containsExactlyElementsOf(ids);
        assertThat(mapper.selectContexts(TENANT_ID, ids).get(0).baselineGroupCount()).isZero();
        tx.executeWithoutResult(status -> {
            // H2 不识别既有群锁查询的 FORCE INDEX，夹具直接建立相同的群主键行锁前置条件。
            jdbc.queryForObject("SELECT id FROM wa_group WHERE tenant_id=? AND id=? FOR UPDATE",
                    Long.class, TENANT_ID, GROUP_ID);
            assertThat(mapper.selectControlledExistingAfterGroupLock(TENANT_ID, GROUP_ID, rows))
                    .extracting(ControlledExisting::accountId).containsExactlyElementsOf(ids.stream()
                            .sorted(Comparator.comparing(id -> "1555000" + id + "@s.whatsapp.net"))
                            .toList());
            mapper.upsertControlledBindings(TENANT_ID, rows);
            List<ControlledExisting> existing = mapper.selectControlledExistingAfterGroupLock(
                    TENANT_ID, GROUP_ID, rows);
            assertThat(existing).allSatisfy(row -> {
                assertThat(row.participantId()).isEqualTo(row.accountId());
                assertThat(row.existing().membershipActiveSinceAt()).isEqualTo(200L);
                assertThat(row.bindingId()).isNotNull();
            });
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wa_account_group_binding", Integer.class))
                .isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT lid_jid FROM wa_group_participant WHERE id=1", String.class))
                .isEqualTo("lid-1@lid");
    }

    @Test
    void explicitTenantFiltersAccountContextGroupParticipantsAndBindings() {
        insertAccount(1, TENANT_ID);
        insertAccount(2, 8L);
        insertAccount(3, TENANT_ID);
        jdbc.update("UPDATE account SET deleted_at=1 WHERE id=3");
        insertParticipant(1, TENANT_ID, GROUP_ID, 1, "FULL_SNAPSHOT", 200);
        jdbc.update("INSERT INTO wa_group VALUES (201,8,'other-group@g.us',NULL)");
        insertParticipant(2, 8L, 201L, 1, "FULL_SNAPSHOT", 200);
        jdbc.update("INSERT INTO wa_group_participant (id,tenant_id,group_id,pn_jid,lid_jid,"
                + "presence_status,presence_source,presence_observed_at) VALUES "
                + "(20,8,101,'15550001@s.whatsapp.net','other@lid',2,'WGP2_LEAVE',900)");
        jdbc.update("INSERT INTO wa_account_group_binding "
                + "(tenant_id,account_id,group_id,participant_id,membership_active_since_at,created_at,updated_at) "
                + "VALUES (8,1,101,20,900,900,900)");
        TenantContext.set(8L);

        assertThat(mapper.selectContexts(TENANT_ID, List.of(1L, 2L, 3L)))
                .extracting(Context::accountId).containsExactly(1L);
        assertThat(mapper.selectContexts(TENANT_ID, List.of())).isEmpty();
        assertThat(mapper.selectControlledExistingAfterGroupLock(TENANT_ID, 201L,
                List.of(write(2, 1, "FULL_SNAPSHOT", 200, 200L)))).isEmpty();
        mapper.upsertControlledBindings(TENANT_ID, List.of(write(1, 1, "FULL_SNAPSHOT", 200, 200L)));
        assertThat(jdbc.queryForObject("SELECT participant_id FROM wa_account_group_binding "
                + "WHERE tenant_id=7", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM wa_account_group_binding "
                + "WHERE participant_id=1", Long.class))
                .isEqualTo(TENANT_ID);
        assertThat(mapper.selectControlledExistingAfterGroupLock(8L, GROUP_ID,
                List.of(write(1, 1, "FULL_SNAPSHOT", 200, 200L)))).isEmpty();
        assertThat(mapper.selectControlledExistingAfterGroupLock(TENANT_ID, GROUP_ID, List.of()))
                .isEmpty();
    }

    @Test
    void upsertRetainsBaselineAndEarliestCycleAndDoesNotAcceptStaleFacts() {
        insertParticipant(1, TENANT_ID, GROUP_ID, 1, "FULL_SNAPSHOT", 300);
        mapper.upsertControlledBindings(TENANT_ID, List.of(write(1, 1, "FULL_SNAPSHOT", 300, 300L)));
        mapper.upsertControlledBindings(TENANT_ID, List.of(write(1, 1, "FULL_SNAPSHOT", 200, 200L)));
        assertThat(jdbc.queryForMap("SELECT membership_active_since_at, last_observed_at, "
                + "first_post_control_observed_at, baseline_subject_snapshot "
                + "FROM wa_account_group_binding"))
                .containsEntry("membership_active_since_at", 200L)
                .containsEntry("last_observed_at", 300L)
                .containsEntry("first_post_control_observed_at", 300L)
                .containsEntry("baseline_subject_snapshot", "baseline-300");
        insertParticipant(2, TENANT_ID, GROUP_ID, 2, "WGP2_LEAVE", 400);
        mapper.upsertControlledBindings(TENANT_ID, List.of(write(2, 1, "FULL_SNAPSHOT", 200, 200L)));
        assertThat(jdbc.queryForMap("SELECT membership_active_since_at, "
                + "was_in_initial_baseline, first_post_control_observed_at "
                + "FROM wa_account_group_binding WHERE account_id=2"))
                .containsEntry("membership_active_since_at", null)
                .containsEntry("was_in_initial_baseline", null)
                .containsEntry("first_post_control_observed_at", null);
    }

    @Test
    void acceptedExitsClearOnlyTheirOwnAccountGroupSourceAndTimestamp() {
        for (long id = 1; id <= 4; id++) {
            insertParticipant(id, TENANT_ID, GROUP_ID, 1, "FULL_SNAPSHOT", 200);
            mapper.upsertControlledBindings(TENANT_ID,
                    List.of(write(id, 1, "FULL_SNAPSHOT", 200, 200L)));
        }
        jdbc.update("UPDATE wa_group_participant SET presence_status=2, "
                + "presence_source='WGP2_LEAVE',presence_observed_at=300 WHERE id IN (1,2,4)");
        jdbc.update("UPDATE wa_group_participant SET presence_observed_at=400 WHERE id=2");
        jdbc.update("UPDATE wa_group_participant SET presence_source='WGP2_REMOVE' WHERE id=4");

        mapper.clearControlledMembershipActiveSinceForAcceptedExits(TENANT_ID, List.of(
                write(1, 2, "WGP2_LEAVE", 300, null),
                write(2, 2, "WGP2_LEAVE", 300, null),
                write(3, 2, "WGP2_LEAVE", 300, null),
                write(4, 2, "WGP2_LEAVE", 300, null)));

        assertThat(jdbc.queryForObject("SELECT membership_active_since_at "
                + "FROM wa_account_group_binding WHERE account_id=1", Long.class)).isNull();
        assertThat(jdbc.queryForList("SELECT membership_active_since_at "
                + "FROM wa_account_group_binding WHERE account_id IN (2,3,4)", Long.class))
                .containsExactly(200L, 200L, 200L);
    }

    @Test
    void failedTransactionRollsBackEveryBindingInTheBatch() {
        insertParticipant(1, TENANT_ID, GROUP_ID, 1, "FULL_SNAPSHOT", 200);
        insertParticipant(2, TENANT_ID, GROUP_ID, 1, "FULL_SNAPSHOT", 200);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            mapper.upsertControlledBindings(TENANT_ID, List.of(
                    write(1, 1, "FULL_SNAPSHOT", 200, 200L),
                    write(2, 1, "FULL_SNAPSHOT", 200, 200L)));
            throw new IllegalStateException("rollback batch");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wa_account_group_binding", Integer.class))
                .isZero();
    }

    @Test
    void batchPreservesMillisecondTimestampsAndLargeAccountIdentifiers() {
        long accountId = 3_000_000_001L;
        long observedAt = 1_789_363_137_123L;
        insertParticipant(accountId, TENANT_ID, GROUP_ID, 1, "FULL_SNAPSHOT", observedAt);
        List<ControlledWrite> rows = List.of(write(accountId, 1, "FULL_SNAPSHOT", observedAt, observedAt));
        mapper.upsertControlledBindings(TENANT_ID, rows);
        assertThat(mapper.selectControlledExistingAfterGroupLock(TENANT_ID, GROUP_ID, rows))
                .singleElement().satisfies(row -> {
                    assertThat(row.accountId()).isEqualTo(accountId);
                    assertThat(row.membershipActiveSinceAt()).isEqualTo(observedAt);
                    assertThat(row.firstPostControlObservedAt()).isEqualTo(observedAt);
                });
    }

    @Test
    void oneAndFifteenAccountsExecuteTheSameThreeNewMapperStatements() {
        for (long id = 1; id <= 15; id++) {
            insertAccount(id, TENANT_ID);
            insertParticipant(id, TENANT_ID, GROUP_ID, 1, "FULL_SNAPSHOT", 1_789_363_137_123L);
        }
        List<String> oneAccountStatements = runCountedBatch(1);
        List<String> fifteenAccountStatements = runCountedBatch(15);

        assertThat(oneAccountStatements).hasSize(3);
        assertThat(fifteenAccountStatements).hasSize(3);
        assertThat(fifteenAccountStatements.get(0)).contains("FROM account a");
        assertThat(fifteenAccountStatements.get(1)).contains("FROM wa_group g", "FOR UPDATE");
        assertThat(fifteenAccountStatements.get(2)).startsWith("INSERT INTO wa_account_group_binding");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wa_group_participant "
                + "WHERE tenant_id=7 AND group_id=101 AND presence_status=1", Integer.class)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wa_account_group_binding "
                + "WHERE tenant_id=7 AND group_id=101", Integer.class)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wa_account_group_binding b "
                + "JOIN wa_group_participant p ON p.id=b.participant_id "
                + "WHERE p.pn_jid=CONCAT('1555000',b.account_id,'@s.whatsapp.net')", Integer.class))
                .isEqualTo(15);
    }

    private List<String> runCountedBatch(int size) {
        List<Long> accountIds = LongStream.rangeClosed(1, size).boxed().toList();
        List<ControlledWrite> rows = accountIds.stream()
                .map(id -> write(id, 1, "FULL_SNAPSHOT", 1_789_363_137_123L, 1_789_363_137_123L))
                .toList();
        statementCounter.statements.clear();
        tx.executeWithoutResult(status -> {
            // 仅计数本次新增的三个 Mapper SQL；旧成员 upsert 方言及实际锁行为由 MySQL 验证。
            jdbc.queryForObject("SELECT id FROM wa_group WHERE tenant_id=? AND id=? FOR UPDATE",
                    Long.class, TENANT_ID, GROUP_ID);
            mapper.selectContexts(TENANT_ID, accountIds);
            mapper.selectControlledExistingAfterGroupLock(TENANT_ID, GROUP_ID, rows);
            mapper.upsertControlledBindings(TENANT_ID, rows);
        });
        return List.copyOf(statementCounter.statements);
    }

    private void insertAccount(long id, long tenantId) {
        jdbc.update("INSERT INTO account VALUES (?,?,?,'ANDROID',?,NULL)",
                id, tenantId, "1555000" + id, "account-" + id);
    }

    private void insertParticipant(long id, long tenantId, long groupId,
                                   int presence, String source, long observedAt) {
        jdbc.update("INSERT INTO wa_group_participant (id,tenant_id,group_id,pn_jid,lid_jid,"
                + "presence_status,presence_source,presence_observed_at) VALUES (?,?,?,?,?,?,?,?)", id, tenantId,
                groupId, "1555000" + id + "@s.whatsapp.net", "lid-" + id + "@lid",
                presence, source, observedAt);
    }

    private ControlledWrite write(long accountId, int presence, String source,
                                  long observedAt, Long activeSince) {
        return new ControlledWrite(accountId, new ParticipantPresenceWrite(
                GROUP_ID, GROUP_JID, "1555000" + accountId + "@s.whatsapp.net", null,
                "1555000" + accountId, presence, source, "event-" + observedAt,
                observedAt, 1000L, null, null, null, null, null, null, null,
                null, null, 0, "baseline-" + observedAt, activeSince, observedAt));
    }

    private void createSchema() {
        jdbc.execute("CREATE TABLE account (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, "
                + "ws_phone VARCHAR(32), protocol_id VARCHAR(32), protocol_account_id VARCHAR(64), deleted_at BIGINT)");
        jdbc.execute("CREATE TABLE account_group_sync_state (tenant_id BIGINT, account_id BIGINT, "
                + "baseline_state INT, baseline_completeness INT, baseline_group_count INT, "
                + "baseline_captured_at BIGINT, last_sync_requested_at BIGINT, last_complete_at BIGINT)");
        jdbc.execute("CREATE TABLE wa_group (id BIGINT PRIMARY KEY, tenant_id BIGINT NOT NULL, "
                + "group_jid VARCHAR(128), deleted_at BIGINT)");
        jdbc.execute("CREATE TABLE wa_group_participant (id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, "
                + "group_id BIGINT NOT NULL, pn_jid VARCHAR(128), lid_jid VARCHAR(128), "
                + "presence_status INT, presence_source VARCHAR(64), presence_observed_at BIGINT, "
                + "phone VARCHAR(32),presence_event_id VARCHAR(128),last_joined_at BIGINT,last_join_event_at BIGINT,"
                + "last_join_source_event_id VARCHAR(128),last_exited_at BIGINT,last_exit_type VARCHAR(64),"
                + "last_exit_event_at BIGINT,last_exit_source_event_id VARCHAR(128),last_exit_source_type VARCHAR(64),"
                + "role INT DEFAULT 0,role_source VARCHAR(64),role_observed_at BIGINT,role_event_id VARCHAR(128),"
                + "last_snapshot_version VARCHAR(128),created_at BIGINT DEFAULT 0,updated_at BIGINT DEFAULT 0,"
                + "CONSTRAINT uq_wa_group_participant_pn UNIQUE(tenant_id,group_id,pn_jid))");
        jdbc.execute("CREATE TABLE wa_account_group_binding (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "tenant_id BIGINT NOT NULL, account_id BIGINT NOT NULL, group_id BIGINT NOT NULL, "
                + "participant_id BIGINT NOT NULL, was_in_initial_baseline INT, baseline_subject_snapshot VARCHAR(255), "
                + "membership_active_since_at BIGINT, last_observed_at BIGINT, first_post_control_observed_at BIGINT, "
                + "created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL, "
                + "CONSTRAINT uq_binding UNIQUE(tenant_id,account_id,group_id))");
    }

    /** 只记录 StatementHandler 已成功执行的真实 JDBC 查询和更新。 */
    @Intercepts({
        @Signature(type = StatementHandler.class, method = "query",
                args = {java.sql.Statement.class, ResultHandler.class}),
        @Signature(type = StatementHandler.class, method = "update", args = java.sql.Statement.class)
    })
    static class StatementCounter implements Interceptor {
        private final List<String> statements = new ArrayList<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            statements.add(((StatementHandler) invocation.getTarget()).getBoundSql().getSql()
                    .replaceAll("\\s+", " ").trim());
            return result;
        }
    }
}
