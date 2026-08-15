package com.armada.group.service.impl;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 新群模型真实 MySQL 测试共用的建表和 SQL 计数工具。 */
final class GroupCurrentSnapshotMySqlTestSupport {

    private GroupCurrentSnapshotMySqlTestSupport() {
    }

    static void createLegacyContextSchema(JdbcTemplate jdbc) {
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

    static void executeV120(DataSource dataSource) throws Exception {
        String sql;
        try (var stream = GroupCurrentSnapshotMySqlTestSupport.class.getResourceAsStream(
                "/db/migration/V120__group_data_model_foundation.sql")) {
            if (stream == null) {
                throw new IllegalStateException("找不到 V120 新群模型迁移");
            }
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

    /** 记录 Mapper 真正执行的 JDBC statement，不统计连接和事务控制。 */
    static final class RecordingDataSource implements DataSource {

        private final DataSource delegate;
        private final CopyOnWriteArrayList<String> statements = new CopyOnWriteArrayList<>();

        RecordingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        void reset() {
            statements.clear();
        }

        List<String> statements() {
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
