package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.hyperlink.task.mapper.HyperlinkTaskContentMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRoundMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRuntimeMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskButtonDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskMessageContentDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskSaveDTO;
import com.armada.hyperlink.task.model.entity.HyperlinkTaskRuntime;
import com.armada.hyperlink.task.model.enums.HyperlinkProvisionStatus;
import com.armada.hyperlink.task.port.HyperlinkTaskAuditPort;
import com.armada.hyperlink.task.service.HyperlinkQuoteTokenService;
import com.armada.hyperlink.task.service.HyperlinkAccountFilterNormalizer;
import com.armada.hyperlink.task.service.HyperlinkCleanupStartService;
import com.armada.hyperlink.task.service.HyperlinkProvisionFactService;
import com.armada.hyperlink.task.service.HyperlinkShortLinkGuard;
import com.armada.hyperlink.task.service.HyperlinkTaskConfigurationFactory;
import com.armada.hyperlink.task.service.HyperlinkTaskLifecycleService;
import com.armada.hyperlink.task.service.HyperlinkTaskQuoteGuardService;
import com.armada.hyperlink.task.service.HyperlinkTaskQuoteService;
import com.armada.hyperlink.task.service.HyperlinkTaskStoreService;
import com.armada.hyperlink.task.service.HyperlinkTaskStateMachine;
import com.armada.hyperlink.template.model.HyperlinkButton;
import com.armada.hyperlink.template.model.HyperlinkMessageContent;
import com.armada.hyperlink.template.model.enums.HyperlinkButtonType;
import com.armada.hyperlink.template.service.HyperlinkMessageContentValidator;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** enabled=false 不得生成 recipient/claim/billing/round 执行事实。 */
class HyperlinkTaskDraftLifecycleTest {

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void draftOnlyWritesTaskContentAndRuntimeSkeleton() {
        HyperlinkTaskMapper taskMapper = mock(HyperlinkTaskMapper.class);
        HyperlinkTaskContentMapper contentMapper = mock(HyperlinkTaskContentMapper.class);
        HyperlinkTaskRuntimeMapper runtimeMapper = mock(HyperlinkTaskRuntimeMapper.class);
        HyperlinkTaskRecipientMapper recipientMapper = mock(HyperlinkTaskRecipientMapper.class);
        HyperlinkTaskRoundMapper roundMapper = mock(HyperlinkTaskRoundMapper.class);
        HyperlinkProvisionFactService provisionFactService = mock(HyperlinkProvisionFactService.class);
        HyperlinkTaskAuditPort audit = mock(HyperlinkTaskAuditPort.class);
        HyperlinkMessageContentValidator validator = mock(HyperlinkMessageContentValidator.class);
        HyperlinkMessageContent normalized = new HyperlinkMessageContent(1, 3, "标题", "正文",
                null, null, List.of(new HyperlinkButton(HyperlinkButtonType.CTA_URL,
                "查看", "https://example.com", false, 1)), null, null, null);
        when(validator.validateAndNormalize(any())).thenReturn(normalized);
        when(taskMapper.insert(any())).thenAnswer(invocation -> {
            invocation.<com.armada.hyperlink.task.model.entity.HyperlinkTask>getArgument(0).setId(91L);
            return 1;
        });
        HyperlinkTaskRuntime stored = new HyperlinkTaskRuntime();
        stored.setHyperlinkTaskId(91L);
        stored.setEnabled(false);
        stored.setRunStatus(0);
        stored.setProvisionStatus(0);
        when(runtimeMapper.selectByTaskId(91L)).thenReturn(stored);
        TenantContext.set(7L);
        ObjectMapper objectMapper = new ObjectMapper();
        var capacity = mock(
                com.armada.hyperlink.task.service.HyperlinkProtocolCapacityService.class);
        HyperlinkTaskLifecycleService service = new HyperlinkTaskLifecycleService(
                new HyperlinkTaskConfigurationFactory(
                        validator, objectMapper, new HyperlinkAccountFilterNormalizer()),
                new HyperlinkTaskStoreService(taskMapper, contentMapper, runtimeMapper),
                mock(HyperlinkTaskQuoteGuardService.class), provisionFactService,
                mock(HyperlinkCleanupStartService.class), roundMapper, audit,
                new HyperlinkShortLinkGuard(""),
                capacity);

        var receipt = service.create(draft(), principal());

        assertThat(receipt.provisionStatus()).isEqualTo(HyperlinkProvisionStatus.NOT_REQUIRED);
        verify(taskMapper).insert(any());
        verify(contentMapper).insert(any());
        verify(runtimeMapper).insert(any());
        verify(provisionFactService, never()).prepare(any(), any(), anyLong());
        verify(capacity, never()).requireSufficient(anyInt());
        verify(audit).requireAvailable();
        verify(audit).record(org.mockito.ArgumentMatchers.argThat(event ->
                event.action() == HyperlinkTaskAuditPort.Action.CREATE
                        && event.taskId() == 91L && event.actorUserId() == 8L));
        verify(roundMapper, never()).insert(any());
        verify(recipientMapper, never()).insertIgnoreBatch(any());
    }

    private HyperlinkTaskSaveDTO draft() {
        return new HyperlinkTaskSaveDTO(null, null, "草稿", 3,
                new HyperlinkTaskMessageContentDTO(null, "标题", null, null, null, "正文", null,
                        List.of(new HyperlinkTaskButtonDTO("CTA_URL", "查看",
                                "https://example.com", false))),
                "instant", null, 0, new HyperlinkAccountFilterDTO(
                1, List.of(), List.of(), null, List.of(), List.of(),
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null),
                BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.7), 31, 31, 0,
                "now", 0, null, false, null);
    }

    private AuthPrincipal principal() {
        return new AuthPrincipal(8L, 7L, "u", "U", "t", "T", List.of(), List.of());
    }
}
