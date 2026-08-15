package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.boot.config.MyBatisConfig;
import com.armada.group.mapper.AccountGroupCurrentSnapshotMapper;
import com.armada.group.model.dto.AccountGroupsReportedEvent;
import com.armada.shared.tenant.TenantContext;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
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

/** 在真实 MySQL RR 下锁定新五表账号群快照的批量写入、分类和锁序。 */
@Testcontainers
class AccountGroupCurrentSnapshotPersistenceMySqlTest {

    private static final long TENANT_ID = 7L;
    private static final long BASELINE_CAPTURED_AT = 1_000L;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.8")
            .withDatabaseName("armada_group_snapshot")
            .withUsername("armada")
            .withPassword("armada")
            .withCommand(
                    "--transaction-isolation=REPEATABLE-READ",
                    "--innodb-deadlock-detect=ON",
                    "--innodb-lock-wait-timeout=5");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static JdbcTemplate jdbc;
    private static RecordingDataSource recordingDataSource;
    private static TransactionTemplate transactionTemplate;
    private static AccountGroupCurrentSnapshotPersistenceImpl persistence;

    @BeforeAll
    static void configureMysqlAndProductionMapper() throws Exception {
        DriverManagerDataSource rawDataSource = new DriverManagerDataSource();
        rawDataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        rawDataSource.setUrl(MYSQL.getJdbcUrl());
        rawDataSource.setUsername(MYSQL.getUsername());
        rawDataSource.setPassword(MYSQL.getPassword());
        jdbc = new JdbcTemplate(rawDataSource);
        createLegacyContextSchema();
        executeV117(rawDataSource);

        recordingDataSource = new RecordingDataSource(rawDataSource);
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(recordingDataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);
        SqlSessionTemplate sqlSessionTemplate = buildSqlSessionTemplate(recordingDataSource);
        AccountGroupCurrentSnapshotMapper mapper =
                sqlSessionTemplate.getMapper(AccountGroupCurrentSnapshotMapper.class);
        persistence = new AccountGroupCurrentSnapshotPersistenceImpl(mapper, OBJECT_MAPPER);
    }

    @AfterAll
    static void clearTenantContext() {
        TenantContext.clear();
    }

    @BeforeEach
    void resetData() {
        jdbc.update("DELETE FROM wa_account_group_binding");
        jdbc.update("DELETE FROM account_group_sync_state");
        jdbc.update("DELETE FROM wa_group_participant");
        jdbc.update("DELETE FROM wa_group_invite");
        jdbc.update("DELETE FROM wa_group_profile");
        jdbc.update("DELETE FROM wa_group");
        jdbc.update("DELETE FROM account_group_baseline");
        jdbc.update("DELETE FROM account");
        recordingDataSource.reset();
    }

    @Test
    void completeSnapshotOf400GroupsUsesAtMostTenStatementsAndClassifiesBaselineSafely()
            throws Exception {
        List<String> baselineJids = groupJids(0, 200);
        seedCapturedAccount(101L, "923300000101", baselineJids);
        List<AccountGroupsReportedEvent.Group> groups = groups(0, 400);
        Collections.reverse(groups);
        groups.add(groups.get(0));

        recordingDataSource.reset();
        writeSnapshot(101L, groups, true, 2_000L, "snapshot-400");

        assertThat(recordingDataSource.statements())
                .as("400 群必须全部走集合 SQL，不能通过 JDBC batch 隐藏服务端语句数")
                .hasSizeLessThanOrEqualTo(10)
                .noneMatch(sql -> sql.startsWith("BATCH "));
        assertThat(count("wa_group")).isEqualTo(400);
        assertThat(count("wa_group_profile")).isEqualTo(400);
        assertThat(count("wa_group_participant")).isEqualTo(400);
        assertThat(count("wa_account_group_binding")).isEqualTo(400);
        assertThat(count("account_group_sync_state")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wa_account_group_binding "
                        + "WHERE was_in_initial_baseline = 1 "
                        + "AND first_post_control_observed_at IS NULL",
                Integer.class)).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wa_account_group_binding "
                        + "WHERE was_in_initial_baseline = 0 "
                        + "AND first_post_control_observed_at = 2000",
                Integer.class)).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wa_account_group_binding "
                        + "WHERE was_in_initial_baseline = 1 "
                        + "AND first_post_control_observed_at IS NOT NULL",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM wa_account_group_binding "
                        + "WHERE membership_active_since_at = 2000",
                Integer.class)).isEqualTo(400);
        assertThat(jdbc.queryForObject(
                "SELECT wa_created_at FROM wa_group_profile p "
                        + "JOIN wa_group g ON g.id = p.group_id "
                        + "WHERE g.group_jid = '120363-snapshot-000@g.us'",
                Long.class)).isEqualTo(1_000_000L);
    }

    @Test
    void replayIsIdempotentAndCompleteSnapshotWritesEveryParticipantBeforeBinding()
            throws Exception {
        seedCapturedAccount(102L, "923300000102", List.of(groupJid(0)));
        List<AccountGroupsReportedEvent.Group> groups = groups(0, 2);
        writeSnapshot(102L, groups, true, 2_000L, "snapshot-first");

        Long firstActiveSince = scalarLong("""
                SELECT b.membership_active_since_at
                FROM wa_account_group_binding b
                JOIN wa_group g ON g.id = b.group_id
                WHERE b.account_id = 102 AND g.group_jid = '120363-snapshot-001@g.us'
                """);
        Long firstPost = scalarLong("""
                SELECT b.first_post_control_observed_at
                FROM wa_account_group_binding b
                JOIN wa_group g ON g.id = b.group_id
                WHERE b.account_id = 102 AND g.group_jid = '120363-snapshot-001@g.us'
                """);

        writeSnapshot(102L, groups, true, 3_000L, "snapshot-replay");
        assertThat(count("wa_group")).isEqualTo(2);
        assertThat(count("wa_group_profile")).isEqualTo(2);
        assertThat(count("wa_group_participant")).isEqualTo(2);
        assertThat(count("wa_account_group_binding")).isEqualTo(2);
        assertThat(scalarLong("""
                SELECT b.membership_active_since_at
                FROM wa_account_group_binding b
                JOIN wa_group g ON g.id = b.group_id
                WHERE b.account_id = 102 AND g.group_jid = '120363-snapshot-001@g.us'
                """)).isEqualTo(firstActiveSince);
        assertThat(scalarLong("""
                SELECT b.first_post_control_observed_at
                FROM wa_account_group_binding b
                JOIN wa_group g ON g.id = b.group_id
                WHERE b.account_id = 102 AND g.group_jid = '120363-snapshot-001@g.us'
                """)).isEqualTo(firstPost);

        recordingDataSource.reset();
        writeSnapshot(102L, List.of(groups.get(0)), true, 4_000L, "snapshot-missing");
        List<String> statements = recordingDataSource.statements();
        assertThat(statements).hasSizeLessThanOrEqualTo(10);
        String classificationRead = statements.stream()
                .filter(sql -> sql.contains("FROM WA_GROUP G")
                        && sql.contains("WA_ACCOUNT_GROUP_BINDING"))
                .findFirst()
                .orElseThrow();
        assertThat(classificationRead).doesNotContain("FOR UPDATE");
        String groupIdCurrentRead = statements.stream()
                .filter(sql -> sql.startsWith("SELECT GROUP_JID AS GROUPJID"))
                .findFirst()
                .orElseThrow();
        assertThat(groupIdCurrentRead)
                .contains("ORDER BY GROUP_JID ASC FOR UPDATE");
        int firstBindingDml = firstIndexContaining(statements, "wa_account_group_binding", "INSERT");
        int lastParticipantDml = lastDmlIndexContaining(statements, "wa_group_participant");
        assertThat(lastParticipantDml).isGreaterThanOrEqualTo(0);
        assertThat(firstBindingDml).isGreaterThan(lastParticipantDml);
        assertThat(jdbc.queryForObject("""
                SELECT p.presence_status
                FROM wa_group_participant p
                JOIN wa_group g ON g.id = p.group_id
                WHERE g.group_jid = '120363-snapshot-001@g.us'
                  AND p.pn_jid = '923300000102@s.whatsapp.net'
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void emptyCompleteSnapshotMarksAllPreviouslyBoundParticipantsMissing() throws Exception {
        seedCapturedAccount(103L, "923300000103", List.of(groupJid(0)));
        writeSnapshot(103L, groups(0, 1), true, 2_000L, "snapshot-visible");

        recordingDataSource.reset();
        writeSnapshot(103L, List.of(), true, 3_000L, "snapshot-empty-complete");

        assertThat(recordingDataSource.statements()).hasSizeLessThanOrEqualTo(10);
        assertThat(jdbc.queryForObject("""
                SELECT p.presence_status
                FROM wa_group_participant p
                JOIN wa_account_group_binding b ON b.participant_id = p.id
                WHERE b.account_id = 103
                """, Integer.class)).isEqualTo(2);
        assertThat(count("wa_account_group_binding")).isEqualTo(1);
    }

    private static void writeSnapshot(
            Long accountId,
            List<AccountGroupsReportedEvent.Group> groups,
            boolean complete,
            long syncAt,
            String eventId) {
        TenantContext.set(TENANT_ID);
        try {
            transactionTemplate.executeWithoutResult(status -> persistence.replaceVisibleGroups(
                    accountId, groups, complete, syncAt, eventId));
        } finally {
            TenantContext.clear();
        }
    }

    private static void seedCapturedAccount(Long accountId, String phone, List<String> baselineJids)
            throws Exception {
        Map<String, String> subjects = new LinkedHashMap<>();
        for (String groupJid : baselineJids) {
            subjects.put(groupJid, "baseline-" + groupJid);
        }
        jdbc.update("""
                INSERT INTO account (
                  id, tenant_id, ws_phone, protocol_id, protocol_account_id,
                  group_baseline_state, created_at, updated_at, deleted_at
                ) VALUES (?, ?, ?, 'web', ?, 2, 100, 100, NULL)
                """, accountId, TENANT_ID, phone, "acc_" + accountId);
        jdbc.update("""
                INSERT INTO account_group_baseline (
                  tenant_id, account_id, baseline_group_jids, baseline_group_subjects,
                  group_count, captured_at, last_group_sync_requested_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 900, 100, 100)
                """, TENANT_ID, accountId,
                OBJECT_MAPPER.writeValueAsString(baselineJids),
                OBJECT_MAPPER.writeValueAsString(subjects),
                baselineJids.size(), BASELINE_CAPTURED_AT);
    }

    private static List<AccountGroupsReportedEvent.Group> groups(int start, int count) {
        List<AccountGroupsReportedEvent.Group> groups = new ArrayList<>(count);
        for (int index = start; index < start + count; index++) {
            groups.add(group(index));
        }
        return groups;
    }

    private static AccountGroupsReportedEvent.Group group(int index) {
        return new AccountGroupsReportedEvent.Group(
                groupJid(index),
                "群-" + index,
                100 + index,
                "923300009999@s.whatsapp.net",
                "923300009999",
                (index & 1) == 0,
                (index & 1) == 1,
                "https://cdn.example/group-" + index + ".jpg",
                1_000L + index);
    }

    private static List<String> groupJids(int start, int count) {
        List<String> values = new ArrayList<>(count);
        for (int index = start; index < start + count; index++) {
            values.add(groupJid(index));
        }
        return values;
    }

    private static String groupJid(int index) {
        return "120363-snapshot-%03d@g.us".formatted(index);
    }

    private static int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static Long scalarLong(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static int firstIndexContaining(List<String> statements, String table, String verb) {
        String normalizedTable = table.toUpperCase(java.util.Locale.ROOT);
        for (int index = 0; index < statements.size(); index++) {
            String sql = statements.get(index);
            if (sql.contains(normalizedTable) && sql.startsWith(verb)) {
                return index;
            }
        }
        return -1;
    }

    private static int lastDmlIndexContaining(List<String> statements, String table) {
        String normalizedTable = table.toUpperCase(java.util.Locale.ROOT);
        for (int index = statements.size() - 1; index >= 0; index--) {
            String sql = statements.get(index);
            if (sql.startsWith("INSERT INTO " + normalizedTable)
                    || sql.startsWith("UPDATE " + normalizedTable)) {
                return index;
            }
        }
        return -1;
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
                new ClassPathResource("mapper/group/AccountGroupCurrentSnapshotMapper.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        if (factory == null) {
            throw new IllegalStateException("无法创建账号群新模型测试 SqlSessionFactory");
        }
        return new SqlSessionTemplate(factory);
    }

    private static void createLegacyContextSchema() {
        jdbc.execute("""
                CREATE TABLE account (
                  id BIGINT NOT NULL,
                  tenant_id BIGINT NOT NULL,
                  ws_phone VARCHAR(64) NOT NULL,
                  protocol_id VARCHAR(32) DEFAULT NULL,
                  protocol_account_id VARCHAR(64) DEFAULT NULL,
                  group_baseline_state TINYINT NOT NULL DEFAULT 1,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  deleted_at BIGINT DEFAULT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uq_account_phone (tenant_id, ws_phone)
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE account_group_baseline (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  tenant_id BIGINT NOT NULL,
                  account_id BIGINT NOT NULL,
                  baseline_group_jids JSON NOT NULL,
                  baseline_group_subjects JSON DEFAULT NULL,
                  group_count INT DEFAULT NULL,
                  captured_at BIGINT DEFAULT NULL,
                  last_group_sync_requested_at BIGINT DEFAULT NULL,
                  created_at BIGINT NOT NULL,
                  updated_at BIGINT NOT NULL,
                  PRIMARY KEY (id),
                  UNIQUE KEY uq_account_group_baseline (tenant_id, account_id)
                ) ENGINE=InnoDB
                """);
    }

    private static void executeV117(DataSource dataSource) throws Exception {
        String sql;
        try (var stream = AccountGroupCurrentSnapshotPersistenceMySqlTest.class.getResourceAsStream(
                "/db/migration/V117__group_data_model_foundation.sql")) {
            assertThat(stream).isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            for (String command : sql.split(";")) {
                if (!command.isBlank()) {
                    statement.execute(command);
                }
            }
        }
    }

    /** 记录一次 mapper 调用真正执行的 JDBC statement，不统计连接和事务控制。 */
    private static final class RecordingDataSource implements DataSource {

        private final DataSource delegate;
        private final CopyOnWriteArrayList<String> statements = new CopyOnWriteArrayList<>();

        private RecordingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        private void reset() {
            statements.clear();
        }

        private List<String> statements() {
            return List.copyOf(statements);
        }

        @Override
        public Connection getConnection() throws SQLException {
            return wrap(delegate.getConnection());
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return wrap(delegate.getConnection(username, password));
        }

        private Connection wrap(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        try {
                            Object value = method.invoke(connection, args);
                            if (value instanceof Statement statement) {
                                String preparedSql = args != null && args.length > 0
                                        && args[0] instanceof String sql ? normalize(sql) : null;
                                return wrap(statement, preparedSql);
                            }
                            return value;
                        } catch (InvocationTargetException exception) {
                            throw exception.getTargetException();
                        }
                    });
        }

        private Statement wrap(Statement statement, String preparedSql) {
            Class<?> type = statement instanceof java.sql.PreparedStatement
                    ? java.sql.PreparedStatement.class : Statement.class;
            return (Statement) Proxy.newProxyInstance(
                    type.getClassLoader(),
                    new Class<?>[]{type},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("executeBatch") || name.equals("executeLargeBatch")) {
                            statements.add("BATCH " + preparedSql);
                        } else if (name.startsWith("execute")) {
                            String sql = preparedSql;
                            if (sql == null && args != null && args.length > 0
                                    && args[0] instanceof String rawSql) {
                                sql = normalize(rawSql);
                            }
                            statements.add(sql == null ? name : sql);
                        }
                        try {
                            return method.invoke(statement, args);
                        } catch (InvocationTargetException exception) {
                            throw exception.getTargetException();
                        }
                    });
        }

        private static String normalize(String sql) {
            return sql.replaceAll("\\s+", " ").trim().toUpperCase();
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }
}
