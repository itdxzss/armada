package com.armada.contact.task;

import com.armada.account.model.dto.AccountHyperlinkCandidateQuery;
import com.armada.account.service.AccountHyperlinkCandidateService;
import com.armada.contact.task.service.ContactAccountSelector;
import com.armada.hyperlink.task.port.HyperlinkPrivateCapabilityPort;
import com.armada.hyperlink.task.service.HyperlinkAccountFilterNormalizer;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 通讯录圈选复用超链任务那套候选能力，这里钉住复用边界不被改回自建一套。 */
class ContactAccountSelectorTest {

    private static final long NOW = 1_700_000_000_000L;

    private AccountHyperlinkCandidateService candidateService;
    private HyperlinkPrivateCapabilityPort capabilityPort;
    private ContactAccountSelector selector;

    @BeforeEach
    void setUp() {
        candidateService = mock(AccountHyperlinkCandidateService.class);
        capabilityPort = mock(HyperlinkPrivateCapabilityPort.class);
        selector = new ContactAccountSelector(
                candidateService,
                new HyperlinkAccountFilterNormalizer(),
                capabilityPort,
                new ObjectMapper(),
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @Test
    void selectsThroughTheSharedCandidateService() {
        allowAllBackends();
        when(candidateService.selectCandidates(any(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of());

        selector.select("{\"countryIso2s\":[\"BR\"],\"contactNamedNumMin\":30}", 10);

        AccountHyperlinkCandidateQuery query = capturedQuery();
        assertThat(query.countryIso2s()).containsExactly("BR");
        assertThat(query.contactNamedNumMin()).isEqualTo(30);
        assertThat(query.observedAt()).isEqualTo(NOW);
    }

    @Test
    void fillsTheSchemaVersionSoCallersDoNotCarryIt() {
        // 竞品空筛选提交的就是 {}，前端不该关心归一化器的版本号约定
        allowAllBackends();
        when(candidateService.countCandidates(any())).thenReturn(5);

        assertThat(selector.count("{}")).isEqualTo(5);
        assertThat(selector.count(null)).isEqualTo(5);
        assertThat(selector.count("   ")).isEqualTo(5);
    }

    @Test
    void keepsTheMutualFriendBoundSeparateFromTheContactCount() {
        // friendCount 是双向好友、至今无采集源；contactNamedNum 才是通讯录计数，
        // 两个口径必须分别下推，混用会让筛选静默命中 0 个号
        allowAllBackends();
        when(candidateService.countCandidates(any())).thenReturn(1);

        selector.count("{\"contactNamedNumMin\":10}");

        AccountHyperlinkCandidateQuery query = capturedCountQuery();
        assertThat(query.contactNamedNumMin()).isEqualTo(10);
        assertThat(query.friendCountMin()).isNull();
    }

    @Test
    void selectsNothingWhenNoBackendCanSendPrivateMessages() {
        when(capabilityPort.supports(any(), any())).thenReturn(false);

        assertThat(selector.select("{}", 10)).isEmpty();
        assertThat(selector.count("{}")).isZero();
        verify(candidateService, never()).selectCandidates(any(), any(), any(), anyInt());
        verify(candidateService, never()).countCandidates(any());
    }

    @Test
    void rejectsAnUnparseableFilterInsteadOfSilentlyWideningTheScope() {
        allowAllBackends();

        assertThatThrownBy(() -> selector.count("{not json"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号筛选条件无法解析");
    }

    @Test
    void normalizesToJsonForStorage() {
        assertThat(selector.normalizeToJson("{\"countryIso2s\":[\"br\"]}"))
                .contains("\"filterSchemaVersion\":1")
                .contains("\"BR\"");
    }

    private void allowAllBackends() {
        for (ProtocolBackend backend : ProtocolBackend.values()) {
            when(capabilityPort.supports(backend, backend.name())).thenReturn(true);
        }
    }

    private AccountHyperlinkCandidateQuery capturedQuery() {
        ArgumentCaptor<AccountHyperlinkCandidateQuery> captor =
                ArgumentCaptor.forClass(AccountHyperlinkCandidateQuery.class);
        verify(candidateService).selectCandidates(captor.capture(), isNull(), isNull(), anyInt());
        return captor.getValue();
    }

    private AccountHyperlinkCandidateQuery capturedCountQuery() {
        ArgumentCaptor<AccountHyperlinkCandidateQuery> captor =
                ArgumentCaptor.forClass(AccountHyperlinkCandidateQuery.class);
        verify(candidateService).countCandidates(captor.capture());
        return captor.getValue();
    }
}
