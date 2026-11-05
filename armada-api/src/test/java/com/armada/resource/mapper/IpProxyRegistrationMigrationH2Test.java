package com.armada.resource.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * 迁移 V224：H2 无法解析 MySQL 专有的 MODIFY COLUMN … COMMENT / AFTER，所以分两部分证明——
 * 对真实迁移文件做结构断言；再剥掉 H2 不支持的子句后真正执行，验证新增列与唯一键的行为。
 * 真实 MySQL 上的执行仍需随部署验证。
 */
class IpProxyRegistrationMigrationH2Test {

    @Test
    void migrationIsOneAtomicAlterWithTheExpectedClauses() throws Exception {
        String sql = migrationWithoutComments();

        assertThat(sql).startsWith("ALTER TABLE ip_proxy");
        // 只有一条语句：MySQL 的 DDL 会隐式提交，多条语句部分失败后无法重跑。
        assertThat(sql.chars().filter(c -> c == ';').count()).isEqualTo(1L);
        assertThat(sql).contains("MODIFY COLUMN status TINYINT NOT NULL DEFAULT 1")
                .contains("4=占用")
                .contains("ADD COLUMN registration_activation_id BIGINT DEFAULT NULL")
                .contains("ADD UNIQUE KEY uq_ip_proxy_registration_activation (registration_activation_id)");
        // 独立归属列，不得改动配对占用与账号绑定的列。
        assertThat(sql).doesNotContain("DROP").doesNotContain("pairing_session_id BIGINT")
                .doesNotContain("bound_account_id BIGINT");
    }

    @Test
    void addedColumnIsUniquePerOrderAndIndependentFromPairingSessionIds() throws Exception {
        String portable = migrationWithoutComments()
                .replaceAll("(?s)MODIFY COLUMN.*?COMMENT '[^']*',", "")
                .replaceAll("COMMENT '[^']*'", "")
                .replaceAll("\\s+AFTER\\s+\\w+", "");
        String url = "jdbc:h2:mem:ip_proxy_registration_migration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            // V068 之后与本迁移相关的列。
            statement.execute("CREATE TABLE ip_proxy (id BIGINT PRIMARY KEY, status TINYINT NOT NULL DEFAULT 1, "
                    + "bound_account_id BIGINT, pairing_session_id BIGINT, "
                    + "UNIQUE KEY uq_ip_proxy_pairing_session (pairing_session_id))");
            // H2 也不接受一条 ALTER 里并列多个 ADD 子句，拆成两条各自执行（取自真实迁移文件的子句）。
            for (String clause : portable.substring(portable.indexOf("ADD COLUMN")).replace(";", "").split(",\\s*(?=ADD )")) {
                statement.execute("ALTER TABLE ip_proxy " + clause.trim());
            }

            statement.execute("INSERT INTO ip_proxy (id, status, registration_activation_id) VALUES (1, 4, 700)");
            // 配对会话号与接码订单号可以相同，因为是两列、两个唯一键。
            statement.execute("INSERT INTO ip_proxy (id, status, pairing_session_id) VALUES (2, 4, 700)");
            // 同一个接码订单不能同时占两个代理。
            assertThatThrownBy(() -> statement.execute(
                    "INSERT INTO ip_proxy (id, status, registration_activation_id) VALUES (3, 4, 700)"))
                    .isInstanceOf(SQLException.class);
            // 非注册占用的行该列为 NULL，可以有任意多行。
            statement.execute("INSERT INTO ip_proxy (id, status) VALUES (4, 1)");
            statement.execute("INSERT INTO ip_proxy (id, status) VALUES (5, 1)");
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM ip_proxy")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(4);
            }
        }
    }

    private static String migrationWithoutComments() throws Exception {
        String raw = new ClassPathResource("db/migration/V224__ip_proxy_registration_reservation.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        return String.join("\n", Arrays.stream(raw.split("\n")).filter(line -> !line.startsWith("--")).toList()).trim();
    }
}
