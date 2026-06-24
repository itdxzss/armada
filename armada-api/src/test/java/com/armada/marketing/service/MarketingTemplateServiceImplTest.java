package com.armada.marketing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.marketing.converter.MarketingTemplateConverter;
import com.armada.marketing.mapper.MarketingTemplateMapper;
import com.armada.marketing.model.ButtonType;
import com.armada.marketing.model.LinkMode;
import com.armada.marketing.model.MessageButton;
import com.armada.marketing.model.dto.MarketingTemplateDTO;
import com.armada.marketing.model.entity.MarketingTemplate;
import com.armada.marketing.service.impl.MarketingTemplateServiceImpl;
import com.armada.shared.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 营销模板保存校验单测(mock mapper/converter,验证业务规则;SQL/真库另由 DbTest 覆盖)。
 */
@ExtendWith(MockitoExtension.class)
class MarketingTemplateServiceImplTest {

    @Mock
    private MarketingTemplateMapper mapper;

    @Mock
    private MarketingTemplateConverter converter;

    @InjectMocks
    private MarketingTemplateServiceImpl service;

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
                .hasMessageContaining("普通超链模式不可配置消息按钮");
    }

    @Test
    void create_buttonModeWithoutButtons_throws() {
        when(mapper.existsByName(any(), isNull())).thenReturn(false);
        assertThatThrownBy(() -> service.create(dto("t", LinkMode.BUTTON.code(), List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少配置 1 个");
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
        verify(mapper, never()).softDeleteByIds(any());
    }

    @Test
    void clone_notFound_throws() {
        when(mapper.selectById(eq(99L))).thenReturn(null);
        assertThatThrownBy(() -> service.clone(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
    }
}
