package com.armada.promotion.pairing.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PromotionCapiEventOutboxSchemaDbTest extends DbTestBase {

    private static final String TABLE = "promotion_capi_event_outbox";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void capiOutboxHasLifecycleAttributionAndDispatchColumns() {
        assertThat(columns()).contains(
                "id", "tenant_id", "promotion_channel_id", "pairing_session_id",
                "event_stage", "event_name", "event_id", "event_time",
                "phone_sha256", "client_ip", "client_user_agent", "fbp", "fbc",
                "event_source_url", "status", "retry_count", "next_retry_at",
                "locked_by", "locked_at", "sent_at", "last_error_code",
                "last_error_message", "sensitive_expires_at", "created_at", "updated_at");
    }

    @Test
    void capiOutboxHasIdempotencyAndDispatchIndexes() {
        assertIndex("uq_promotion_capi_session_stage", true,
                List.of("tenant_id", "pairing_session_id", "event_stage"));
        assertIndex("uq_promotion_capi_event_id", true, List.of("event_id"));
        assertIndex("idx_promotion_capi_dispatch", false,
                List.of("status", "next_retry_at", "id"));
        assertIndex("idx_promotion_capi_lock_recovery", false,
                List.of("status", "locked_at", "id"));
        assertIndex("idx_promotion_capi_sensitive_expiry", false,
                List.of("sensitive_expires_at", "id"));
        assertIndex("idx_promotion_capi_channel", false,
                List.of("tenant_id", "promotion_channel_id", "created_at"));
    }

    private List<String> columns() {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = ?",
                String.class,
                TABLE);
    }

    private void assertIndex(String name, boolean unique, List<String> expectedColumns) {
        List<IndexColumn> actual = jdbc.query(
                "SELECT non_unique, column_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ? "
                        + "ORDER BY seq_in_index",
                (rs, rowNum) -> new IndexColumn(rs.getInt("non_unique"), rs.getString("column_name")),
                TABLE,
                name);
        assertThat(actual).extracting(IndexColumn::columnName)
                .containsExactlyElementsOf(expectedColumns);
        assertThat(actual).allMatch(column -> unique
                ? column.nonUnique() == 0
                : column.nonUnique() == 1);
    }

    private record IndexColumn(int nonUnique, String columnName) {
    }
}
