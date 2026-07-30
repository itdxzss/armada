package com.armada.promotion.pairing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.promotion.channel.model.dto.PromotionChannelCapiEventDTO;
import com.armada.promotion.channel.model.vo.PromotionChannelCapiDeliveryResult;
import com.armada.promotion.channel.service.PromotionChannelService;
import com.armada.promotion.pairing.mapper.PromotionCapiEventOutboxMapper;
import com.armada.promotion.pairing.model.entity.PromotionCapiEventOutbox;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

@ExtendWith(MockitoExtension.class)
class PromotionCapiEventDispatcherTest {

    @Mock
    private PromotionCapiEventOutboxMapper outboxMapper;
    @Mock
    private PromotionChannelService channelService;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void springCreatesDispatcherThroughItsDependencyInjectionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PromotionCapiEventOutboxMapper.class, () -> outboxMapper);
            context.registerBean(PromotionChannelService.class, () -> channelService);
            context.register(PromotionCapiEventDispatcher.class);

            context.refresh();

            assertThat(context.getBean(PromotionCapiEventDispatcher.class)).isNotNull();
        }
    }

    @Test
    void successfulDeliveryUsesRowTenantAndClearsSensitiveOutboxViaMarkSent() {
        PromotionCapiEventOutbox row = row(0);
        stubClaim(row);
        when(channelService.deliverFacebookCapi(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    assertThat(TenantContext.get()).isEqualTo(7L);
                    return new PromotionChannelCapiDeliveryResult(true, false, null, null);
                });
        TenantContext.set(99L);
        PromotionCapiEventDispatcher dispatcher =
                new PromotionCapiEventDispatcher(outboxMapper, channelService, 10, 6, 30_000L, "worker-1");

        int delivered = dispatcher.dispatchOnce();

        assertThat(delivered).isEqualTo(1);
        assertThat(TenantContext.get()).isEqualTo(99L);
        verify(outboxMapper).markSent(eq(row), anyLong());
        ArgumentCaptor<PromotionChannelCapiEventDTO> command =
                ArgumentCaptor.forClass(PromotionChannelCapiEventDTO.class);
        verify(channelService).deliverFacebookCapi(command.capture());
        assertThat(command.getValue().eventId()).isEqualTo("capi_event_1");
        assertThat(command.getValue().phoneSha256()).hasSize(64);
    }

    @Test
    void retryableFailureStoresOnlyStableCodeAndGenericMessage() {
        PromotionCapiEventOutbox row = row(0);
        stubClaim(row);
        when(channelService.deliverFacebookCapi(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PromotionChannelCapiDeliveryResult(
                        false, true, "HTTP_503", "secret-token=must-not-persist"));
        PromotionCapiEventDispatcher dispatcher =
                new PromotionCapiEventDispatcher(outboxMapper, channelService, 10, 6, 30_000L, "worker-1");

        dispatcher.dispatchOnce();

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(outboxMapper).markRetry(
                eq(row), anyLong(), eq("HTTP_503"), message.capture(), anyLong());
        assertThat(message.getValue()).doesNotContain("secret-token");
        verify(outboxMapper, never()).markDead(
                eq(row), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), anyLong());
    }

    @Test
    void permanentFailureStopsRetrying() {
        PromotionCapiEventOutbox row = row(0);
        stubClaim(row);
        when(channelService.deliverFacebookCapi(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PromotionChannelCapiDeliveryResult(
                        false, false, "UNCONFIGURED", "details"));
        PromotionCapiEventDispatcher dispatcher =
                new PromotionCapiEventDispatcher(outboxMapper, channelService, 10, 6, 30_000L, "worker-1");

        dispatcher.dispatchOnce();

        verify(outboxMapper).markDead(
                eq(row), eq("UNCONFIGURED"), eq("Meta CAPI 正式事件投递失败"), anyLong());
        verify(outboxMapper, never()).markRetry(
                eq(row), anyLong(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), anyLong());
    }

    @Test
    void claimsEachCandidateImmediatelyBeforeItsDelivery() {
        PromotionCapiEventOutbox first = row(1L, 0);
        PromotionCapiEventOutbox second = row(2L, 0);
        when(outboxMapper.selectDispatchable(anyLong(), eq(10)))
                .thenReturn(List.of(first, second));
        when(outboxMapper.markLocked(anyList(), eq("worker-1"), anyLong())).thenReturn(1);
        when(outboxMapper.selectLocked(anyList(), eq("worker-1"), anyLong()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<Long> ids = invocation.getArgument(0);
                    PromotionCapiEventOutbox claimed = ids.get(0).equals(first.getId()) ? first : second;
                    claimed.setLockedBy("worker-1");
                    claimed.setLockedAt(invocation.getArgument(2));
                    return List.of(claimed);
                });
        when(channelService.deliverFacebookCapi(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PromotionChannelCapiDeliveryResult(true, false, null, null));
        PromotionCapiEventDispatcher dispatcher =
                new PromotionCapiEventDispatcher(outboxMapper, channelService, 10, 6, 30_000L, "worker-1");

        assertThat(dispatcher.dispatchOnce()).isEqualTo(2);

        InOrder order = org.mockito.Mockito.inOrder(outboxMapper, channelService);
        order.verify(outboxMapper).markLocked(eq(List.of(1L)), eq("worker-1"), anyLong());
        order.verify(channelService).deliverFacebookCapi(org.mockito.ArgumentMatchers.any());
        order.verify(outboxMapper).markLocked(eq(List.of(2L)), eq("worker-1"), anyLong());
        order.verify(channelService).deliverFacebookCapi(org.mockito.ArgumentMatchers.any());
    }

    private void stubClaim(PromotionCapiEventOutbox row) {
        when(outboxMapper.selectDispatchable(anyLong(), eq(10))).thenReturn(List.of(row));
        when(outboxMapper.markLocked(anyList(), eq("worker-1"), anyLong())).thenReturn(1);
        when(outboxMapper.selectLocked(anyList(), eq("worker-1"), anyLong()))
                .thenAnswer(invocation -> {
                    row.setLockedBy("worker-1");
                    row.setLockedAt(invocation.getArgument(2));
                    return List.of(row);
                });
    }

    private static PromotionCapiEventOutbox row(int retryCount) {
        return row(1L, retryCount);
    }

    private static PromotionCapiEventOutbox row(long id, int retryCount) {
        PromotionCapiEventOutbox row = new PromotionCapiEventOutbox();
        row.setId(id);
        row.setTenantId(7L);
        row.setPromotionChannelId(501L);
        row.setEventName("Lead");
        row.setEventId("capi_event_" + id);
        row.setEventTime(1_800_000_000L);
        row.setPhoneSha256("a".repeat(64));
        row.setRetryCount(retryCount);
        return row;
    }
}
