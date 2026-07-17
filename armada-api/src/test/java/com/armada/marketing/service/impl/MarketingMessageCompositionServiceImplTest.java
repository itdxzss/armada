package com.armada.marketing.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.marketing.model.vo.MarketingComposedMessageVO;
import com.armada.marketing.service.MarketingMessageComposer;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 营销域对外模板消息组合边界测试。 */
@ExtendWith(MockitoExtension.class)
class MarketingMessageCompositionServiceImplTest {

    @Mock
    private MarketingTemplateMapper templateMapper;
    @Mock
    private MarketingTemplateFileMapper fileMapper;
    @Mock
    private MarketingMessageComposer messageComposer;

    private MarketingMessageCompositionServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.set(71L);
        service = new MarketingMessageCompositionServiceImpl(templateMapper, fileMapper, messageComposer);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void rejectsTemplateThatDoesNotBelongToCurrentTenant() {
        MarketingTemplate foreign = template(801L, 72L, null);
        when(templateMapper.selectById(801L)).thenReturn(foreign);

        assertThatThrownBy(() -> service.compose(801L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("营销模板不存在");

        verify(fileMapper, never()).selectById(org.mockito.ArgumentMatchers.anyLong());
        verify(messageComposer, never()).compose(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void loadsTemplateImageAndPreservesCardsMediaButtonsAndMentionAll() {
        MarketingTemplate template = template(801L, 71L, 901L);
        MarketingTemplateFile file = new MarketingTemplateFile();
        file.setId(901L);
        file.setTenantId(71L);
        byte[] thumbnail = {1, 2, 3};
        MarketingMessageComposer.ComposedMessage composed = new MarketingMessageComposer.ComposedMessage(
                "BUTTON_CARD",
                "完整模板",
                null,
                null,
                null,
                new MarketingMessageComposer.ButtonCardPayload(
                        "标题",
                        "页脚",
                        List.of(new MarketingMessageComposer.ButtonPayload("link", "打开", "https://example.com")),
                        new MarketingMessageComposer.MediaPayload(thumbnail, "image/png")),
                true);
        when(templateMapper.selectById(801L)).thenReturn(template);
        when(fileMapper.selectById(901L)).thenReturn(file);
        when(messageComposer.compose(template, file)).thenReturn(composed);

        MarketingComposedMessageVO result = service.compose(801L);

        assertThat(result.messageType()).isEqualTo("BUTTON_CARD");
        assertThat(result.mentionAll()).isTrue();
        assertThat(result.buttonCard().buttons())
                .containsExactly(new MarketingComposedMessageVO.ButtonVO("link", "打开", "https://example.com"));
        assertThat(result.buttonCard().thumbnail().bytes()).containsExactly(thumbnail);
        assertThat(result.buttonCard().thumbnail().mimetype()).isEqualTo("image/png");
    }

    private static MarketingTemplate template(Long id, Long tenantId, Long imageFileId) {
        MarketingTemplate row = new MarketingTemplate();
        row.setId(id);
        row.setTenantId(tenantId);
        row.setImageFileId(imageFileId);
        return row;
    }
}
