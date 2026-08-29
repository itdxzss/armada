package com.armada.marketing.asset.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.armada.marketing.asset.converter.ResourceAssetConverter;
import com.armada.marketing.asset.mapper.ResourceAssetTagMapper;
import com.armada.marketing.asset.model.vo.ResourceAssetTagRelationVO;
import com.armada.marketing.asset.model.vo.ResourceAssetVO;
import com.armada.marketing.asset.service.ResourceAssetImageValidator;
import com.armada.marketing.asset.service.ResourceAssetWriteService;
import com.armada.marketing.mapper.MarketingTemplateFileMapper;
import com.armada.marketing.model.entity.MarketingTemplateFile;
import com.armada.marketing.service.MarketingTemplateFileService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

/** 图片素材业务层 multipart 解析、元数据保存和租户组装测试。 */
@ExtendWith(MockitoExtension.class)
class ResourceAssetServiceImplTest {

    @Mock
    private MarketingTemplateFileMapper fileMapper;

    @Mock
    private ResourceAssetTagMapper tagMapper;

    @Mock
    private MarketingTemplateFileService fileService;

    @Mock
    private ResourceAssetWriteService writeService;

    @Mock
    private ResourceAssetConverter converter;

    private ResourceAssetServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.set(7L);
        service = new ResourceAssetServiceImpl(
                fileMapper, tagMapper, fileService, writeService, converter);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void uploadDecodesDimensionsAndNormalizesJsonTagsBeforeWrite() throws Exception {
        MockMultipartFile image = jpeg(" promo.JPG ", 3, 2);
        AtomicReference<MarketingTemplateFile> saved = new AtomicReference<>();
        doAnswer(invocation -> {
            MarketingTemplateFile file = invocation.getArgument(0);
            file.setId(88L);
            saved.set(file);
            return 88L;
        }).when(writeService).create(any(MarketingTemplateFile.class), any());
        when(fileMapper.selectAssetMetadataById(88L)).thenAnswer(ignored -> saved.get());
        when(tagMapper.selectRelationsByFileIds(List.of(88L)))
                .thenReturn(List.of(
                        new ResourceAssetTagRelationVO(88L, "Promo"),
                        new ResourceAssetTagRelationVO(88L, "promo")));
        when(fileMapper.selectReferenceCounts(7L, List.of(88L))).thenReturn(List.of());
        ResourceAssetVO response = new ResourceAssetVO(
                88L, "promo.JPG", "/api/resource-assets/88/content", List.of("Promo", "promo"),
                image.getSize(), 3, 2, 0, 11L, 100L, 100L);
        when(converter.toVO(any(MarketingTemplateFile.class), any(), anyLong())).thenReturn(response);

        assertThat(service.upload(image, "[\" Promo \",\"promo\",\"Promo\"]", 11L))
                .isSameAs(response);
        assertThat(saved.get()).satisfies(file -> {
            assertThat(file.getOriginalFilename()).isEqualTo("promo.JPG");
            assertThat(file.getAssetName()).isEqualTo("promo.JPG");
            assertThat(file.getContentType()).isEqualTo("image/jpeg");
            assertThat(file.getWidth()).isEqualTo(3);
            assertThat(file.getHeight()).isEqualTo(2);
            assertThat(file.getCreatedBy()).isEqualTo(11L);
            assertThat(file.getUpdatedAt()).isEqualTo(file.getCreatedAt());
        });
        verify(writeService).create(saved.get(), List.of("Promo", "promo"));
    }

    @Test
    void uploadRejectsNonStringJsonTagsBeforeAnyWrite() throws Exception {
        MockMultipartFile image = jpeg("promo.jpg", 2, 2);

        assertThatThrownBy(() -> service.upload(image, "[\"Promo\",1]", 11L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("tags 必须是 JSON 字符串数组");
        verifyNoInteractions(writeService);
    }

    @Test
    void uploadRejectsTrailingGarbageAfterTagsArray() throws Exception {
        MockMultipartFile image = jpeg("promo.jpg", 2, 2);

        assertThatThrownBy(() -> service.upload(image, "[\"Promo\"] trailing", 11L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("tags 必须是 JSON 字符串数组");
        verifyNoInteractions(writeService);
    }

    @Test
    void uploadRejectsOversizedMultipartBeforeReadingOrWriting() {
        MockMultipartFile image = new MockMultipartFile(
                "file",
                "large.jpg",
                "image/jpeg",
                new byte[ResourceAssetImageValidator.MAX_IMAGE_BYTES + 1]);

        assertThatThrownBy(() -> service.upload(image, "[]", 11L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("500KB");
        verifyNoInteractions(writeService);
    }

    private static MockMultipartFile jpeg(String filename, int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpg", output);
            return new MockMultipartFile("file", filename, "image/jpeg", output.toByteArray());
        }
    }
}
