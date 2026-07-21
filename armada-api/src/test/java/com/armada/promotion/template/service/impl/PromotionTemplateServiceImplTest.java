package com.armada.promotion.template.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.promotion.template.mapper.PromotionTemplateMapper;
import com.armada.promotion.template.model.dto.PromotionTemplateQuery;
import com.armada.promotion.template.model.vo.PromotionTemplateRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class PromotionTemplateServiceImplTest {

    @Mock
    private PromotionTemplateMapper mapper;

    private PromotionTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PromotionTemplateServiceImpl(mapper, new ObjectMapper());
    }

    @Test
    void pageUsesDefaultTwentyAndConvertsSupportedParameterCodes() {
        PromotionTemplateQuery query = new PromotionTemplateQuery();
        PromotionTemplateRow row = row();
        when(mapper.countPage(query)).thenReturn(5L);
        when(mapper.selectPage(query)).thenReturn(List.of(row));

        var result = service.page(query);

        assertThat(result.page()).isEqualTo(1);
        assertThat(result.pageSize()).isEqualTo(20);
        assertThat(result.total()).isEqualTo(5);
        assertThat(result.list()).singleElement().satisfies(item -> {
            assertThat(item.subaccountVisible()).isTrue();
            assertThat(item.supportedParams())
                    .extracting(param -> param.code() + ":" + param.label())
                    .containsExactly("themeColor:主题色", "showAppDownload:展示底部应用下载");
        });
        verify(mapper).selectPage(query);
    }

    @Test
    void pageSkipsListQueryWhenCountIsZero() {
        PromotionTemplateQuery query = new PromotionTemplateQuery();
        when(mapper.countPage(query)).thenReturn(0L);

        var result = service.page(query);

        assertThat(result.list()).isEmpty();
        assertThat(result.total()).isZero();
        verify(mapper, never()).selectPage(query);
    }

    @Test
    void pageUsesReadOnlyTransactionForConsistentCountAndListSnapshot() throws Exception {
        Transactional transactional = PromotionTemplateServiceImpl.class
                .getMethod("page", PromotionTemplateQuery.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }

    private static PromotionTemplateRow row() {
        PromotionTemplateRow row = new PromotionTemplateRow();
        row.setId(39L);
        row.setTemplateCode("basic_party_man");
        row.setTemplateName("基础约会-投男粉");
        row.setPreviewUri("/preview/basic_party_man.png");
        row.setIsSubaccountVisible(1);
        row.setSupportedParamsJson("[\"themeColor\",\"showAppDownload\"]");
        row.setCreatedAt(1779719349000L);
        row.setUpdatedAt(1779719349000L);
        return row;
    }
}
