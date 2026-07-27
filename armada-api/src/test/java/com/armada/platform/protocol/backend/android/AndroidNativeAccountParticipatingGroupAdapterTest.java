package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountGroupMetadataSummaryResult;
import com.armada.platform.protocol.model.result.AccountParticipatingGroupResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AndroidNativeAccountParticipatingGroupAdapterTest {

    @Mock
    private AndroidNativeClient client;

    @Test
    void readsCurrentGroupsAndSummariesThroughExistingListEndpoint() throws Exception {
        AndroidResponseEnvelope response = envelope("""
                {"Code":0,"Data":{"Count":1,"GroupInfos":[{
                  "group_id":"120363001@g.us",
                  "subject":"安卓历史群",
                  "participants":[
                    {"phone_number":"919000000001","type":"admin"},
                    {"phone_number":"919000000002","type":"participant"}
                  ]
                }]},"Msg":"ok"}
                """);
        when(client.groups("919000000001")).thenReturn(response);

        AndroidNativeAccountParticipatingGroupAdapter adapter = adapter();
        List<AccountParticipatingGroupResult.Group> groups = adapter.listCurrent(account());
        List<AccountGroupMetadataSummaryResult> summaries = adapter.summarize(
                account(), List.of("120363001@g.us"), 8);

        assertThat(groups).singleElement().satisfies(group -> {
            assertThat(group.groupJid()).isEqualTo("120363001@g.us");
            assertThat(group.subject()).isEqualTo("安卓历史群");
        });
        assertThat(summaries).singleElement().satisfies(summary -> {
            assertThat(summary.success()).isTrue();
            assertThat(summary.selfRole()).isEqualTo("ADMIN");
            assertThat(summary.memberSize()).isEqualTo(2);
            assertThat(summary.announceOnly()).isNull();
        });
    }

    @Test
    void addsAndroidContextToListAndSummaryFailures() throws Exception {
        when(client.groups("919000000001"))
                .thenReturn(envelope("""
                        {"Code":1003,"Data":null,"Msg":"request timeout"}
                        """));

        assertContext(
                () -> adapter().listCurrent(account()),
                "account.groups.current");
        assertContext(
                () -> adapter().summarize(account(), List.of("120363001@g.us"), 8),
                "account.groups.metadata-summaries");
    }

    private void assertContext(Runnable call, String operation) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ProtocolException.class, ex -> {
                    assertThat(ex.errorCode()).isEqualTo(ProtocolErrorCode.TIMEOUT);
                    assertThat(ex.backend()).contains(ProtocolBackend.ANDROID);
                    assertThat(ex.operation()).contains(operation);
                    assertThat(ex.operationId()).contains("armada-account:7");
                });
    }

    private AndroidNativeAccountParticipatingGroupAdapter adapter() {
        return new AndroidNativeAccountParticipatingGroupAdapter(
                client,
                new AndroidResponseDecoder(),
                new AndroidGroupOperationErrorMapper(),
                new AndroidAccountParticipatingGroupMapper(new AndroidGroupMemberMapper()));
    }

    private static ProtocolAccountRef account() {
        return new ProtocolAccountRef(
                7L,
                ProtocolBackend.ANDROID,
                "android_7",
                "919000000001");
    }

    private static AndroidResponseEnvelope envelope(String json) throws Exception {
        return new ObjectMapper().readValue(json, AndroidResponseEnvelope.class);
    }
}
