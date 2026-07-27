package com.armada.promotion.pairing.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.promotion.pairing.model.entity.PromotionCapiEventOutbox;
import com.armada.promotion.pairing.model.enums.PromotionCapiEventStage;
import com.armada.promotion.pairing.model.enums.PromotionCapiEventStatus;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class PromotionCapiEventOutboxMapperDbTest extends DbTestBase {

    @Autowired
    private PromotionCapiEventOutboxMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void threeStageSnapshotActivatesClaimsAndClearsSensitiveDataAtTerminalState() {
        long now = System.currentTimeMillis();
        long sessionId = now;
        mapper.batchInsert(List.of(
                row(sessionId, PromotionCapiEventStage.LEAD, PromotionCapiEventStatus.PENDING, now),
                row(sessionId, PromotionCapiEventStage.LOGIN_REQUEST, PromotionCapiEventStatus.WAITING, now),
                row(sessionId, PromotionCapiEventStage.LOGIN_SUCCESS, PromotionCapiEventStatus.WAITING, now)));

        assertThat(mapper.activate(sessionId, PromotionCapiEventStage.LOGIN_REQUEST.code(), now + 1, now + 1))
                .isEqualTo(1);
        assertThat(mapper.activate(sessionId, PromotionCapiEventStage.LOGIN_REQUEST.code(), now + 2, now + 2))
                .isZero();

        List<PromotionCapiEventOutbox> candidates = mapper.selectDispatchable(now + 2, 10);
        assertThat(candidates).extracting(PromotionCapiEventOutbox::getEventStage)
                .containsExactly(
                        PromotionCapiEventStage.LEAD.code(),
                        PromotionCapiEventStage.LOGIN_REQUEST.code());

        PromotionCapiEventOutbox lead = candidates.get(0);
        assertThat(mapper.markLocked(List.of(lead.getId()), "test-dispatcher", now + 3)).isEqualTo(1);
        PromotionCapiEventOutbox locked = mapper.selectLocked(
                List.of(lead.getId()), "test-dispatcher", now + 3).get(0);
        assertThat(mapper.markSent(locked, now + 4)).isEqualTo(1);

        TerminalState terminal = jdbc.queryForObject(
                "SELECT status, phone_sha256, client_ip, client_user_agent, fbp, fbc, event_source_url "
                        + "FROM promotion_capi_event_outbox WHERE id = ?",
                (rs, rowNum) -> new TerminalState(
                        rs.getInt("status"),
                        rs.getString("phone_sha256"),
                        rs.getString("client_ip"),
                        rs.getString("client_user_agent"),
                        rs.getString("fbp"),
                        rs.getString("fbc"),
                        rs.getString("event_source_url")),
                lead.getId());
        assertThat(terminal.status()).isEqualTo(PromotionCapiEventStatus.SENT.code());
        assertThat(terminal.phoneSha256()).isNull();
        assertThat(terminal.clientIp()).isNull();
        assertThat(terminal.clientUserAgent()).isNull();
        assertThat(terminal.fbp()).isNull();
        assertThat(terminal.fbc()).isNull();
        assertThat(terminal.eventSourceUrl()).isNull();
    }

    @Test
    void cancellationOnlyAffectsWaitingStagesAndClearsTheirAttribution() {
        long now = System.currentTimeMillis();
        long sessionId = now + 100;
        mapper.batchInsert(List.of(
                row(sessionId, PromotionCapiEventStage.LEAD, PromotionCapiEventStatus.PENDING, now),
                row(sessionId, PromotionCapiEventStage.LOGIN_REQUEST, PromotionCapiEventStatus.WAITING, now),
                row(sessionId, PromotionCapiEventStage.LOGIN_SUCCESS, PromotionCapiEventStatus.WAITING, now)));

        assertThat(mapper.cancelWaiting(sessionId, now + 1)).isEqualTo(2);
        List<Integer> statuses = jdbc.queryForList(
                "SELECT status FROM promotion_capi_event_outbox "
                        + "WHERE tenant_id = ? AND pairing_session_id = ? ORDER BY event_stage",
                Integer.class,
                TEST_TENANT_ID,
                sessionId);
        assertThat(statuses).containsExactly(
                PromotionCapiEventStatus.PENDING.code(),
                PromotionCapiEventStatus.CANCELED.code(),
                PromotionCapiEventStatus.CANCELED.code());
    }

    @Test
    void privacyRetentionDeadlineTerminatesExpiredSensitiveRows() {
        long now = System.currentTimeMillis();
        PromotionCapiEventOutbox expired = row(
                now + 200, PromotionCapiEventStage.LEAD, PromotionCapiEventStatus.PENDING, now);
        expired.setSensitiveExpiresAt(now - 1);
        mapper.batchInsert(List.of(expired));

        assertThat(mapper.scrubExpiredSensitive(now, now - 300_000L, 10)).isEqualTo(1);

        TerminalState terminal = jdbc.queryForObject(
                "SELECT status, phone_sha256, client_ip, client_user_agent, fbp, fbc, event_source_url "
                        + "FROM promotion_capi_event_outbox WHERE event_id = ?",
                (rs, rowNum) -> new TerminalState(
                        rs.getInt("status"), rs.getString("phone_sha256"),
                        rs.getString("client_ip"), rs.getString("client_user_agent"),
                        rs.getString("fbp"), rs.getString("fbc"), rs.getString("event_source_url")),
                expired.getEventId());
        assertThat(terminal.status()).isEqualTo(PromotionCapiEventStatus.DEAD.code());
        assertThat(terminal.phoneSha256()).isNull();
        assertThat(terminal.clientIp()).isNull();
        assertThat(terminal.clientUserAgent()).isNull();
        assertThat(terminal.fbp()).isNull();
        assertThat(terminal.fbc()).isNull();
        assertThat(terminal.eventSourceUrl()).isNull();
    }

    private static PromotionCapiEventOutbox row(
            long sessionId,
            PromotionCapiEventStage stage,
            PromotionCapiEventStatus status,
            long now) {
        PromotionCapiEventOutbox row = new PromotionCapiEventOutbox();
        row.setPromotionChannelId(51L);
        row.setPairingSessionId(sessionId);
        row.setEventStage(stage.code());
        row.setEventName(stage.defaultEventName());
        row.setEventId("capi_" + sessionId + "_" + stage.code());
        row.setEventTime(status == PromotionCapiEventStatus.PENDING ? now / 1000 : null);
        row.setPhoneSha256("a".repeat(64));
        row.setClientIp("203.0.113.9");
        row.setClientUserAgent("Mozilla/5.0");
        row.setFbp("fb.1.1700000000000.1");
        row.setFbc("fb.1.1700000000000.CLICK");
        row.setEventSourceUrl("https://go.example.com/code");
        row.setStatus(status.code());
        row.setRetryCount(0);
        row.setNextRetryAt(0L);
        row.setSensitiveExpiresAt(now + 604_800_000L);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private record TerminalState(
            int status,
            String phoneSha256,
            String clientIp,
            String clientUserAgent,
            String fbp,
            String fbc,
            String eventSourceUrl) {
    }
}
