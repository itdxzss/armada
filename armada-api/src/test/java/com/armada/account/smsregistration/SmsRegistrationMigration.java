package com.armada.account.smsregistration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;

/** H2 不支持 MySQL PREPARE；提取真实迁移中的 DDL 执行，并检查防重入结构。 */
final class SmsRegistrationMigration {
    private SmsRegistrationMigration() { }

    static void apply(DataSource source) throws Exception {
        String sql = new ClassPathResource("db/migration/V228__sms_registration_account_type.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(sql).contains("information_schema.COLUMNS", "TABLE_NAME='sms_registration'",
                "COLUMN_NAME='account_type'", "PREPARE sms_reg_stmt", "DEALLOCATE PREPARE sms_reg_stmt");
        var ddl = Pattern.compile("'(ALTER TABLE .*?)'\\);", Pattern.DOTALL).matcher(sql);
        assertThat(ddl.find()).isTrue();
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute(ddl.group(1).replace("''", "'"));
        }
    }
}
