package com.armada.marketing.service;

import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.marketing.service.impl.MarketingTemplateFileServiceImpl;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 营销模板图片文件服务单测。
 */
@ExtendWith(MockitoExtension.class)
class MarketingTemplateFileServiceImplTest {

    private static final long USER_ID = 11L;

    @Mock
    private MarketingTemplateFileMapper mapper;

    @InjectMocks
    private MarketingTemplateFileServiceImpl service;

    @BeforeEach
    void openDataScope() {
        DataScopeContext.open(DataScope.self(USER_ID));
    }

    @AfterEach
    void clearDataScope() {
        DataScopeContext.clear();
    }

    @Test
    void uploadImage_smallImage_persistsBytesAndReturnsPreviewUrl() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "promo.png", "image/png", new byte[] {1, 2, 3});
        ArgumentCaptor<MarketingTemplateFile> captor = ArgumentCaptor.forClass(MarketingTemplateFile.class);
        doAnswer(invocation -> {
            MarketingTemplateFile row = invocation.getArgument(0);
            row.setId(77L);
            return 1;
        }).when(mapper).insert(any(MarketingTemplateFile.class));

        var result = service.uploadImage(file);

        verify(mapper).insert(captor.capture());
        MarketingTemplateFile saved = captor.getValue();
        assertThat(saved.getOriginalFilename()).isEqualTo("promo.png");
        assertThat(saved.getContentType()).isEqualTo("image/png");
        assertThat(saved.getSizeBytes()).isEqualTo(3);
        assertThat(saved.getContent()).containsExactly(1, 2, 3);
        assertThat(saved.getOwnerUserId()).isEqualTo(USER_ID);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(result.id()).isEqualTo(77L);
        assertThat(result.url()).isEqualTo("/api/marketing-template-files/77/content");
    }

    @Test
    void uploadImage_largeImage_persistsBytes() {
        byte[] bytes = new byte[600 * 1024];
        Arrays.fill(bytes, (byte) 1);
        MockMultipartFile file = new MockMultipartFile("file", "large.png", "image/png", bytes);
        ArgumentCaptor<MarketingTemplateFile> captor = ArgumentCaptor.forClass(MarketingTemplateFile.class);
        doAnswer(invocation -> {
            MarketingTemplateFile row = invocation.getArgument(0);
            row.setId(78L);
            return 1;
        }).when(mapper).insert(any(MarketingTemplateFile.class));

        var result = service.uploadImage(file);

        verify(mapper).insert(captor.capture());
        MarketingTemplateFile saved = captor.getValue();
        assertThat(saved.getOriginalFilename()).isEqualTo("large.png");
        assertThat(saved.getContentType()).isEqualTo("image/png");
        assertThat(saved.getSizeBytes()).isEqualTo(bytes.length);
        assertThat(saved.getContent()).hasSize(bytes.length);
        assertThat(result.id()).isEqualTo(78L);
    }

    @Test
    void contentReadsOnlyThroughCurrentUserScope() {
        MarketingTemplateFile row = new MarketingTemplateFile();
        row.setId(77L);
        row.setOwnerUserId(USER_ID);
        row.setContentType("image/png");
        row.setContent(new byte[] {4, 5, 6});
        when(mapper.selectByIdForScope(eq(77L), any())).thenReturn(row);

        var result = service.content(77L);

        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.content()).containsExactly(4, 5, 6);
    }

    @Test
    void contentHidesForeignOrHistoricalFileFromOrdinaryUser() {
        when(mapper.selectByIdForScope(eq(88L), any())).thenReturn(null);

        assertThatThrownBy(() -> service.content(88L))
                .isInstanceOf(com.armada.shared.exception.BusinessException.class)
                .hasMessageContaining("营销模板图片不存在");
    }
}
