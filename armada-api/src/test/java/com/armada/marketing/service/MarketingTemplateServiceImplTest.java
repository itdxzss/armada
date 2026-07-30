package com.armada.marketing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.marketing.converter.MarketingTemplateConverter;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.mapper.MarketingTaskMapper;
import com.armada.marketing.model.ButtonType;
import com.armada.marketing.model.LinkMode;
import com.armada.marketing.model.MessageButton;
import com.armada.marketing.model.dto.MarketingTemplateDTO;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.service.impl.MarketingTemplateServiceImpl;
import com.armada.marketing.service.impl.MarketingAccountOccupancyService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 营销模板保存校验单测(mock mapper/converter,验证业务规则;SQL/真库另由 DbTest 覆盖)。
 */
@ExtendWith(MockitoExtension.class)
class MarketingTemplateServiceImplTest {

    private static final long TENANT_ID = 1L;

    @Mock
    private MarketingTemplateMapper mapper;

    @Mock
    private MarketingTaskMapper taskMapper;

    @Mock
    private MarketingTemplateConverter converter;

    @Mock
    private MarketingAccountOccupancyService occupancyService;

    @InjectMocks
    private MarketingTemplateServiceImpl service;

    @BeforeEach
    void setTenantContext() {
        TenantContext.set(TENANT_ID);
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    private MarketingTemplateDTO dto(String name, int linkMode, List<MessageButton> buttons) {
        return new MarketingTemplateDTO(name, linkMode, "PROMO", null, "内容", "正文", buttons, null, "备注");
    }

    @Test
    void create_blankName_throws() {
        assertThatThrownBy(() -> service.create(dto(" ", LinkMode.NORMAL.code(), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模板名称不能为空");
        verify(mapper, never()).insert(any());
    }

    @Test
    void create_duplicateName_throwsConflict() {
        when(mapper.existsByName(eq("dup"), isNull())).thenReturn(true);
        assertThatThrownBy(() -> service.create(dto("dup", LinkMode.NORMAL.code(), null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
        verify(mapper, never()).insert(any());
    }

    @Test
    void create_normalModeWithButtons_throws() {
        when(mapper.existsByName(any(), isNull())).thenReturn(false);
        List<MessageButton> buttons = List.of(new MessageButton(ButtonType.QUICK_REPLY, "回复", null));
        assertThatThrownBy(() -> service.create(dto("t", LinkMode.NORMAL.code(), buttons)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("普通超链消息类型不可配置消息按钮");
    }

    @Test
    void create_buttonModeWithoutButtons_throws() {
        when(mapper.existsByName(any(), isNull())).thenReturn(false);
        assertThatThrownBy(() -> service.create(dto("t", LinkMode.BUTTON.code(), List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少配置 1 个");
    }

    @Test
    void create_buttonModeClearsPromotionLinkBeforeInsert() {
        when(mapper.existsByName(any(), isNull())).thenReturn(false);
        MarketingTemplate entity = new MarketingTemplate();
        entity.setPromotionLink("https://promo.example/legacy");
        when(converter.toEntity(any())).thenReturn(entity);
        when(mapper.selectById(any())).thenReturn(entity);
        List<MessageButton> buttons = List.of(new MessageButton(
                ButtonType.LINK_JUMP,
                "访问",
                "https://button.example/open"));

        service.create(new MarketingTemplateDTO(
                "按钮模板",
                LinkMode.BUTTON.code(),
                "PROMO",
                null,
                "内容",
                "",
                buttons,
                "https://promo.example/legacy",
                "备注"));

        assertThat(entity.getPromotionLink()).isNull();
        verify(mapper).insert(entity);
    }

    @Test
    void create_buttonModeIgnoresInvalidPromotionLinkBeforeInsert() {
        when(mapper.existsByName(any(), isNull())).thenReturn(false);
        MarketingTemplate entity = new MarketingTemplate();
        entity.setPromotionLink("not-a-url");
        when(converter.toEntity(any())).thenReturn(entity);
        when(mapper.selectById(any())).thenReturn(entity);
        List<MessageButton> buttons = List.of(new MessageButton(
                ButtonType.LINK_JUMP,
                "访问",
                "https://button.example/open"));

        service.create(new MarketingTemplateDTO(
                "按钮模板",
                LinkMode.BUTTON.code(),
                "PROMO",
                null,
                "内容",
                "",
                buttons,
                "not-a-url",
                "备注"));

        assertThat(entity.getPromotionLink()).isNull();
        verify(mapper).insert(entity);
    }

    @Test
    void create_imageTextModeWithoutButtons_insertsAndReturnsVO() {
        when(mapper.existsByName(any(), isNull())).thenReturn(false);
        MarketingTemplate entity = new MarketingTemplate();
        when(converter.toEntity(any())).thenReturn(entity);
        when(mapper.selectById(any())).thenReturn(entity);

        service.create(dto("图文模板", LinkMode.IMAGE_TEXT.code(), null));

        verify(mapper).insert(entity);
        verify(converter).toVO(entity);
    }

    @Test
    void create_blankBodyText_insertsAndReturnsVO() {
        when(mapper.existsByName(any(), isNull())).thenReturn(false);
        MarketingTemplate entity = new MarketingTemplate();
        when(converter.toEntity(any())).thenReturn(entity);
        when(mapper.selectById(any())).thenReturn(entity);

        service.create(new MarketingTemplateDTO(
                "无文本模板",
                LinkMode.NORMAL.code(),
                "PROMO",
                null,
                "内容",
                " ",
                null,
                "https://promo.example/vip",
                "备注"));

        verify(mapper).insert(entity);
        verify(converter).toVO(entity);
    }

    @Test
    void create_imageTextModeWithButtons_throws() {
        when(mapper.existsByName(any(), isNull())).thenReturn(false);
        List<MessageButton> buttons = List.of(new MessageButton(ButtonType.QUICK_REPLY, "回复", null));

        assertThatThrownBy(() -> service.create(dto("图文模板", LinkMode.IMAGE_TEXT.code(), buttons)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("图文内容消息类型不可配置消息按钮");
    }

    @Test
    void create_tooManyButtons_throws() {
        when(mapper.existsByName(any(), isNull())).thenReturn(false);
        MessageButton b = new MessageButton(ButtonType.QUICK_REPLY, "回复", null);
        assertThatThrownBy(() -> service.create(dto("t", LinkMode.BUTTON.code(), List.of(b, b, b, b))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最多 3");
    }

    @Test
    void create_linkJumpButtonWithoutParam_throws() {
        when(mapper.existsByName(any(), isNull())).thenReturn(false);
        List<MessageButton> buttons = List.of(new MessageButton(ButtonType.LINK_JUMP, "去看看", " "));
        assertThatThrownBy(() -> service.create(dto("t", LinkMode.BUTTON.code(), buttons)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须填写参数");
    }

    @Test
    void create_invalidPromotionLink_throws() {
        when(mapper.existsByName(any(), isNull())).thenReturn(false);

        MarketingTemplateDTO request = new MarketingTemplateDTO(
                "t",
                LinkMode.NORMAL.code(),
                "PROMO",
                null,
                "内容",
                "正文",
                null,
                "not-a-url",
                "备注");

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("推广链接格式不正确");
    }

    @Test
    void create_linkJumpButtonWithInvalidUrl_throws() {
        when(mapper.existsByName(any(), isNull())).thenReturn(false);
        List<MessageButton> buttons = List.of(new MessageButton(ButtonType.LINK_JUMP, "去看看", "abc"));

        assertThatThrownBy(() -> service.create(dto("t", LinkMode.BUTTON.code(), buttons)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("跳转链接格式不正确");
    }

    @Test
    void create_valid_insertsAndReturnsVO() {
        when(mapper.existsByName(any(), isNull())).thenReturn(false);
        MarketingTemplate entity = new MarketingTemplate();
        when(converter.toEntity(any())).thenReturn(entity);
        when(mapper.selectById(any())).thenReturn(entity);

        service.create(dto("新模板", LinkMode.NORMAL.code(), null));

        verify(mapper).insert(entity);
        verify(converter).toVO(entity);
    }

    @Test
    void batchDelete_empty_noop() {
        service.batchDelete(List.of());
        verify(mapper, never()).selectExistingIdsForUpdate(anyLong(), any());
        verify(taskMapper, never()).completeActiveTasksByTemplateIds(any(), anyLong());
        verify(occupancyService, never()).releaseAccountsByTemplateIds(any());
        verify(mapper, never()).softDeleteByIds(any(), anyLong());
    }

    @Test
    void batchDelete_missingTenant_rejectsBeforeLockingTemplates() {
        TenantContext.clear();

        assertThatThrownBy(() -> service.batchDelete(List.of(1L)))
                .isInstanceOf(BusinessException.class);

        verify(mapper, never()).selectExistingIdsForUpdate(anyLong(), any());
    }

    @Test
    void batchDelete_locksTemplatesInStableOrderBeforeCompletingTasksAndSoftDelete() {
        service.batchDelete(Arrays.asList(2L, null, 1L, 2L));

        InOrder ordered = inOrder(mapper, taskMapper, occupancyService);
        ordered.verify(mapper).selectExistingIdsForUpdate(TENANT_ID, List.of(1L, 2L));
        ordered.verify(taskMapper).completeActiveTasksByTemplateIds(eq(List.of(1L, 2L)), anyLong());
        ordered.verify(occupancyService).releaseAccountsByTemplateIds(List.of(1L, 2L));
        ordered.verify(mapper).softDeleteByIds(eq(List.of(1L, 2L)), anyLong());
    }

    @Test
    void batchDelete_activeGroupPullTask_rejectsWithoutChangingTasksOrTemplates() {
        when(taskMapper.countActiveGroupPullTasksByTemplateIds(List.of(1L))).thenReturn(1);

        assertThatThrownBy(() -> service.batchDelete(List.of(1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("模板正在被拉群营销任务使用，不能删除");

        verify(taskMapper, never()).completeActiveTasksByTemplateIds(any(), anyLong());
        verify(occupancyService, never()).releaseAccountsByTemplateIds(any());
        verify(mapper, never()).softDeleteByIds(any(), anyLong());
    }

    @Test
    void clone_notFound_throws() {
        when(mapper.selectById(eq(99L))).thenReturn(null);
        assertThatThrownBy(() -> service.clone(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void clone_copiesMentionAllSetting() {
        MarketingTemplate origin = new MarketingTemplate();
        origin.setTemplateName("全员模板");
        origin.setLinkMode(LinkMode.NORMAL.code());
        origin.setMentionAll(true);
        when(mapper.selectById(7L)).thenReturn(origin);

        service.clone(7L);

        ArgumentCaptor<MarketingTemplate> inserted = ArgumentCaptor.forClass(MarketingTemplate.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getMentionAll()).isTrue();
    }
}
