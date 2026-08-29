package com.armada.platform.kafka.consumer.contact;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 通讯录快照事件消费器测试。 */
@ExtendWith(MockitoExtension.class)
class ProtocolAccountContactEventConsumerTest {

    private static final String CONTACTS =
            "\"contacts\":[{\"phone\":\"8613800000000\",\"jid\":\"8613800000000@s.whatsapp.net\","
                    + "\"fullName\":\"甲\",\"pushName\":\"昵称\",\"businessName\":\"公司\"}]";

    private static final String FULL_DATA =
            "\"tenantId\":5,\"accountId\":11,\"protocolAccountId\":\"acc_1\","
                    + "\"snapshotId\":\"snap-1\","
                    + "\"queryStartedAt\":\"2026-08-29T10:00:00.000Z\","
                    + "\"snapshotCutoff\":\"2026-08-29T10:00:05.000Z\","
                    + "\"snapshotComplete\":true,\"chunkSeq\":0,\"chunkCount\":2,\"totalCount\":3,"
                    + CONTACTS;

    @Mock
    private AccountContactsReportedSink sink;

    private ProtocolAccountContactEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ProtocolAccountContactEventConsumer(new ObjectMapper(), sink);
    }

    private static String envelope(String dataBody) {
        return "{\"eventId\":\"evt_1\",\"event\":\"account.contacts_reported\",\"version\":\"v1\","
                + "\"accountId\":\"acc_1\",\"occurredAt\":\"2026-08-29T10:00:00.000Z\","
                + "\"workerId\":\"w1\",\"data\":{" + dataBody + "}}";
    }

    private AccountContactsReportedEvent captured() {
        ArgumentCaptor<AccountContactsReportedEvent> captor =
                ArgumentCaptor.forClass(AccountContactsReportedEvent.class);
        verify(sink).handle(captor.capture());
        return captor.getValue();
    }

    @Test
    void parsesSnapshotChunkAndDispatchesToSink() {
        consumer.onMessage(envelope(FULL_DATA), null);

        AccountContactsReportedEvent event = captured();
        assertThat(event.tenantId()).isEqualTo(5L);
        assertThat(event.accountId()).isEqualTo(11L);
        assertThat(event.snapshotId()).isEqualTo("snap-1");
        assertThat(event.snapshotComplete()).isTrue();
        assertThat(event.chunkSeq()).isZero();
        assertThat(event.chunkCount()).isEqualTo(2);
        assertThat(event.totalCount()).isEqualTo(3);
        assertThat(event.contacts()).singleElement().satisfies(contact -> {
            assertThat(contact.phone()).isEqualTo("8613800000000");
            assertThat(contact.fullName()).isEqualTo("甲");
            assertThat(contact.pushName()).isEqualTo("昵称");
            assertThat(contact.businessName()).isEqualTo("公司");
            assertThat(contact.firstName()).isNull();
        });
    }

    @Test
    void convertsIso8601CutoffToEpochMillis() {
        consumer.onMessage(envelope(FULL_DATA), null);

        assertThat(captured().snapshotCutoff())
                .isEqualTo(Instant.parse("2026-08-29T10:00:05.000Z").toEpochMilli());
    }

    @Test
    void acceptsEmptyContactChunk() {
        // 「这个号一个联系人都没有」必须能表达，否则残留永远清不掉
        String data = FULL_DATA.replace(CONTACTS, "\"contacts\":[]")
                .replace("\"totalCount\":3", "\"totalCount\":0");

        consumer.onMessage(envelope(data), null);

        assertThat(captured().contacts()).isEmpty();
        assertThat(captured().totalCount()).isZero();
    }

    @Test
    void rejectsMissingSnapshotCutoff() {
        String data = FULL_DATA.replace("\"snapshotCutoff\":\"2026-08-29T10:00:05.000Z\",", "");

        assertThatThrownBy(() -> consumer.onMessage(envelope(data), null))
                .hasMessageContaining("snapshotCutoff");
        verifyNoInteractions(sink);
    }

    @Test
    void rejectsUnparseableSnapshotCutoff() {
        String data = FULL_DATA.replace("2026-08-29T10:00:05.000Z", "not-a-time");

        assertThatThrownBy(() -> consumer.onMessage(envelope(data), null))
                .hasMessageContaining("snapshotCutoff");
        verifyNoInteractions(sink);
    }

    @Test
    void rejectsMissingAccountId() {
        String data = FULL_DATA.replace("\"accountId\":11,", "");

        assertThatThrownBy(() -> consumer.onMessage(envelope(data), null))
                .hasMessageContaining("accountId");
        verifyNoInteractions(sink);
    }

    @Test
    void rejectsMissingContactsArray() {
        // 缺 contacts 与「空数组」是两回事：前者是坏消息，后者是「一个联系人都没有」的事实
        String data = FULL_DATA.replace("," + CONTACTS, "");

        assertThatThrownBy(() -> consumer.onMessage(envelope(data), null))
                .hasMessageContaining("contacts");
        verifyNoInteractions(sink);
    }

    @Test
    void skipsUnrelatedEventType() {
        String raw = envelope(FULL_DATA).replace(
                "account.contacts_reported", "account.state_changed");

        consumer.onMessage(raw, null);

        verifyNoInteractions(sink);
    }
}
