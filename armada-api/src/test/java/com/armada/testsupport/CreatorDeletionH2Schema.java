package com.armada.testsupport;

/** 账号排他生命周期的 H2 表结构，供原账号/拉群 Mapper 回归共用。 */
public final class CreatorDeletionH2Schema {
    private CreatorDeletionH2Schema() { }
    /** H2 等价生成列及复合索引，须在账号表建立后调用。 */
    public static void installIdentityIndex(javax.sql.DataSource source) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
        jdbc.execute("ALTER TABLE account ADD COLUMN IF NOT EXISTS creator_deletion_identity_phone VARCHAR(32) "
                + "GENERATED ALWAYS AS (REPLACE(REPLACE(TRIM(ws_phone),'+',''),' ',''))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_account_creator_deletion_identity "
                + "ON account(creator_deletion_identity_phone,id)");
    }

    public static final String DDL = """
            CREATE TABLE IF NOT EXISTS account_creator_deletion(
              account_id BIGINT PRIMARY KEY,tenant_id BIGINT NOT NULL,task_id BIGINT NOT NULL,
              group_execution_id BIGINT NOT NULL,identity_hash CHAR(64) NOT NULL UNIQUE,
              creator_phone VARCHAR(32),protocol_account_id VARCHAR(128),create_operation_id VARCHAR(128),
              operation_id VARCHAR(128) UNIQUE,lifecycle VARCHAR(16),created_at BIGINT,updated_at BIGINT,
              completed_at BIGINT,UNIQUE(tenant_id,group_execution_id))
            """;
}
