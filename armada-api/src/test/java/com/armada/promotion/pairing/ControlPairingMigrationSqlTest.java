package com.armada.promotion.pairing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** 控台认证码导号会话迁移的结构合同。 */
class ControlPairingMigrationSqlTest {

    @Test
    void migrationAddsSceneAndControlTargetWithoutWeakeningActivePhoneLock() throws Exception {
        String migration;
        try (var stream = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(
                "db/migration/V179__control_account_pairing_session.sql"))) {
            migration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(migration)
                .contains("pairing_scene TINYINT NOT NULL DEFAULT 1")
                .contains("account_group_id BIGINT DEFAULT NULL")
                .contains("promotion_channel_id BIGINT DEFAULT NULL")
                .contains("channel_name VARCHAR(128) DEFAULT NULL")
                .contains("idx_pairing_tenant_scene_created");

        String foundation;
        try (var stream = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(
                "db/migration/V069__promotion_pairing_session.sql"))) {
            foundation = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(foundation).contains("UNIQUE KEY uq_promotion_pairing_active_phone (active_phone)");
    }
}
