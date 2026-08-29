package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.model.dto.AccountHyperlinkCandidateQuery;
import com.armada.account.model.vo.AccountHyperlinkCandidateVO;
import com.armada.account.service.AccountHyperlinkCandidateService;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.port.HyperlinkPrivateCapabilityPort;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 冻结快照运行时须完整归一化并下推账号域，禁止静默丢画像条件。 */
class HyperlinkAccountCandidateSelectorTest {

    @Test
    void pushesAccountProfileFactsIntoAccountDomainQuery() {
        AccountHyperlinkCandidateService accountService =
                mock(AccountHyperlinkCandidateService.class);
        HyperlinkPrivateCapabilityPort capabilityPort = mock(HyperlinkPrivateCapabilityPort.class);
        when(capabilityPort.supports(ProtocolBackend.WEB, "WEB")).thenReturn(true);
        HyperlinkAccountCandidateSelector selector = new HyperlinkAccountCandidateSelector(
                accountService, capabilityPort, new ObjectMapper(),
                new HyperlinkAccountFilterNormalizer());
        HyperlinkTask task = new HyperlinkTask();
        task.setAccountFilter("""
                {"filterSchemaVersion":1,"rotationStatus":2,"groupInviteAllowed":true,
                 "source":4,"friendCountMin":10,"friendCountMax":20,
                 "registerDaysMin":90,"registerDaysMax":180}
                """);

        assertThat(selector.select(task, null, null, 10, 2_000_000_000_000L)).isEmpty();
        ArgumentCaptor<AccountHyperlinkCandidateQuery> query =
                ArgumentCaptor.forClass(AccountHyperlinkCandidateQuery.class);
        org.mockito.Mockito.verify(accountService).selectCandidates(
                query.capture(), isNull(), isNull(), eq(10));
        assertThat(query.getValue().rotationStatus()).isEqualTo(2);
        assertThat(query.getValue().groupInviteAllowed()).isTrue();
        assertThat(query.getValue().source()).isEqualTo(4);
        assertThat(query.getValue().friendCountMin()).isEqualTo(10);
        assertThat(query.getValue().friendCountMax()).isEqualTo(20);
        assertThat(query.getValue().registerDaysMin()).isEqualTo(90);
        assertThat(query.getValue().registerDaysMax()).isEqualTo(180);
    }

    @Test
    void normalizesStoredSnapshotAndPushesPrivateCapableBackendsBeforeLimit() {
        AccountHyperlinkCandidateService accountService =
                mock(AccountHyperlinkCandidateService.class);
        HyperlinkPrivateCapabilityPort capabilityPort = mock(HyperlinkPrivateCapabilityPort.class);
        when(capabilityPort.supports(ProtocolBackend.WEB, "WEB")).thenReturn(true);
        AccountHyperlinkCandidateVO selected = new AccountHyperlinkCandidateVO(
                1L, 7, "5511", "BR", 2, 100L, "WEB", "acc-1", "WEB");
        when(accountService.selectCandidates(any(), isNull(), isNull(), eq(2)))
                .thenReturn(List.of(selected));
        HyperlinkAccountCandidateSelector selector = new HyperlinkAccountCandidateSelector(
                accountService, capabilityPort, new ObjectMapper(),
                new HyperlinkAccountFilterNormalizer());
        HyperlinkTask task = new HyperlinkTask();
        task.setAccountFilter("""
                {"filterSchemaVersion":1,"countryIso2s":["br","BR"],
                 "groupIds":[9,3,9],"onlineStatus":"online"}
                """);

        assertThat(selector.select(task, null, null, 2, 2_000_000_000_000L))
                .containsExactly(selected);
        ArgumentCaptor<AccountHyperlinkCandidateQuery> query =
                ArgumentCaptor.forClass(AccountHyperlinkCandidateQuery.class);
        org.mockito.Mockito.verify(accountService).selectCandidates(
                query.capture(), isNull(), isNull(), eq(2));
        assertThat(query.getValue().countryIso2s()).containsExactly("BR");
        assertThat(query.getValue().groupIds()).containsExactly(3L, 9L);
        assertThat(query.getValue().onlineStatus()).isEqualTo("ONLINE");
        assertThat(query.getValue().privateCapableBackends()).containsExactly("WEB");
    }

    @Test
    void rejectsInvalidStoredSnapshotRangeBeforeCallingAccountDomain() {
        AccountHyperlinkCandidateService accountService =
                mock(AccountHyperlinkCandidateService.class);
        HyperlinkAccountCandidateSelector selector = new HyperlinkAccountCandidateSelector(
                accountService, mock(HyperlinkPrivateCapabilityPort.class), new ObjectMapper(),
                new HyperlinkAccountFilterNormalizer());
        HyperlinkTask task = new HyperlinkTask();
        task.setAccountFilter("""
                {"filterSchemaVersion":1,"retentionDaysMin":10,"retentionDaysMax":1}
                """);

        assertThatThrownBy(() -> selector.select(
                task, null, null, 10, 2_000_000_000_000L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("retentionDays 最小值不能大于最大值");
        org.mockito.Mockito.verifyNoInteractions(accountService);
    }

    @Test
    void countPushesNormalizedFilterToDatabaseCountWithoutLoadingCandidateRows() {
        AccountHyperlinkCandidateService accountService =
                mock(AccountHyperlinkCandidateService.class);
        HyperlinkPrivateCapabilityPort capabilityPort = mock(HyperlinkPrivateCapabilityPort.class);
        when(capabilityPort.supports(ProtocolBackend.WEB, "WEB")).thenReturn(true);
        when(accountService.countCandidates(any())).thenReturn(23);
        HyperlinkAccountCandidateSelector selector = new HyperlinkAccountCandidateSelector(
                accountService, capabilityPort, new ObjectMapper(),
                new HyperlinkAccountFilterNormalizer());
        var filter = new com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO(
                1, List.of("br", "BR"), List.of(), null, List.of(9L), List.of(),
                null, "online", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);

        assertThat(selector.count(filter, 2_000_000_000_000L)).isEqualTo(23);

        ArgumentCaptor<AccountHyperlinkCandidateQuery> query =
                ArgumentCaptor.forClass(AccountHyperlinkCandidateQuery.class);
        verify(accountService).countCandidates(query.capture());
        verify(accountService, never()).selectCandidates(any(), any(), any(),
                org.mockito.ArgumentMatchers.anyInt());
        assertThat(query.getValue().countryIso2s()).containsExactly("BR");
        assertThat(query.getValue().onlineStatus()).isEqualTo("ONLINE");
        assertThat(query.getValue().privateCapableBackends()).containsExactly("WEB");
    }

    @Test
    void protocolCountUsesTheSamePrivateCapabilityFactSourceAsCandidateSelection() {
        AccountHyperlinkCandidateService accountService =
                mock(AccountHyperlinkCandidateService.class);
        HyperlinkPrivateCapabilityPort capabilityPort = mock(HyperlinkPrivateCapabilityPort.class);
        when(capabilityPort.supports(ProtocolBackend.WEB, "WEB")).thenReturn(true);
        when(accountService.countProtocols(List.of("WEB"))).thenReturn(3);
        HyperlinkAccountCandidateSelector selector = new HyperlinkAccountCandidateSelector(
                accountService, capabilityPort, new ObjectMapper(),
                new HyperlinkAccountFilterNormalizer());

        assertThat(selector.protocolCount()).isEqualTo(3);
        verify(accountService).countProtocols(List.of("WEB"));
    }
}
