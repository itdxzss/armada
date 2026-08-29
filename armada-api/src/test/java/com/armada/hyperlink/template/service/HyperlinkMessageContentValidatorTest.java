package com.armada.hyperlink.template.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.armada.hyperlink.template.model.HyperlinkButton;
import com.armada.hyperlink.template.model.HyperlinkMessageContent;
import com.armada.hyperlink.template.model.enums.HyperlinkButtonType;
import com.armada.marketing.model.vo.MarketingTemplateFileContent;
import com.armada.marketing.service.MarketingTemplateFileService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

/** 共享超链消息内容归一化和素材绑定规则测试。 */
class HyperlinkMessageContentValidatorTest {

    private FakeMarketingTemplateFileService fileService;

    private HyperlinkMessageContentValidator validator;

    @BeforeEach
    void setUp() {
        fileService = new FakeMarketingTemplateFileService();
        validator = new HyperlinkMessageContentValidator(fileService);
    }

    @Test
    void singleLinkPreviewRequiresJpegAndClearsFieldsFromOtherTypes() throws IOException {
        fileService.put(11L, new MarketingTemplateFileContent("image/jpeg", jpegBytes()));
        HyperlinkMessageContent input = new HyperlinkMessageContent(
                1, 1, "  标题  ", "  正文  ", "  灰字  ",
                " https://example.com/promo ",
                List.of(button()), "遗留卡片", 11L, 12L);

        HyperlinkMessageContent normalized = validator.validateAndNormalize(input);

        assertThat(normalized.title()).isEqualTo("标题");
        assertThat(normalized.content()).isEqualTo("正文");
        assertThat(normalized.linkDescription()).isEqualTo("灰字");
        assertThat(normalized.promotionLink()).isEqualTo("https://example.com/promo");
        assertThat(normalized.buttons()).isEmpty();
        assertThat(normalized.cardText()).isNull();
        assertThat(normalized.linkPreviewAssetId()).isEqualTo(11L);
        assertThat(normalized.bodyMainAssetId()).isNull();
    }

    @Test
    void normalButtonKeepsOneCtaAndClearsLinkPreviewFields() throws IOException {
        fileService.put(12L, new MarketingTemplateFileContent("image/jpeg", jpegBytes()));
        HyperlinkMessageContent input = new HyperlinkMessageContent(
                1, 3, "按钮标题", "  可选正文  ", "遗留描述",
                "https://legacy.example/path", List.of(button()), "遗留卡片", 11L, 12L);

        HyperlinkMessageContent normalized = validator.validateAndNormalize(input);

        assertThat(normalized.content()).isEqualTo("可选正文");
        assertThat(normalized.linkDescription()).isNull();
        assertThat(normalized.promotionLink()).isNull();
        assertThat(normalized.buttons()).containsExactly(new HyperlinkButton(
                HyperlinkButtonType.CTA_URL, "立即查看", "https://example.com/promo", true, 1));
        assertThat(normalized.cardText()).isNull();
        assertThat(normalized.linkPreviewAssetId()).isNull();
        assertThat(normalized.bodyMainAssetId()).isEqualTo(12L);
    }

    @Test
    void cardButtonRequiresCardTextAndKeepsNormalizedValue() {
        HyperlinkMessageContent missingCardText = new HyperlinkMessageContent(
                1, 4, "标题", null, null, null, List.of(button()), "  ", null, null);
        assertThatThrownBy(() -> validator.validateAndNormalize(missingCardText))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("卡片底部文字");

        HyperlinkMessageContent normalized = validator.validateAndNormalize(new HyperlinkMessageContent(
                1, 4, "标题", null, "遗留描述", "https://legacy.example",
                List.of(button()), "  卡片说明  ", 11L, null));

        assertThat(normalized.cardText()).isEqualTo("卡片说明");
        assertThat(normalized.linkDescription()).isNull();
        assertThat(normalized.promotionLink()).isNull();
        assertThat(normalized.linkPreviewAssetId()).isNull();
    }

    @Test
    void doubleImageTextIsRejectedWithFrozenMessage() {
        HyperlinkMessageContent input = new HyperlinkMessageContent(
                1, 2, "标题", null, null, null, List.of(), null, null, null);

        assertThatThrownBy(() -> validator.validateAndNormalize(input))
                .isInstanceOf(BusinessException.class)
                .hasMessage("一期暂不支持双图文");
    }

    @Test
    void buttonRequiresExactlyOneCtaUrl() {
        HyperlinkMessageContent input = new HyperlinkMessageContent(
                1, 3, "标题", null, null, null, List.of(), null, null, null);

        assertThatThrownBy(() -> validator.validateAndNormalize(input))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("恰好配置 1 个 URL 按钮");
    }

    @Test
    void templateAndTaskSharedTitleLimitIsWidenedLosslesslyTo1024() {
        String accepted = "标".repeat(1024);
        HyperlinkMessageContent normalized = validator.validateAndNormalize(new HyperlinkMessageContent(
                1, 3, accepted, null, null, null, List.of(button()), null, null, null));
        assertThat(normalized.title()).hasSize(1024);

        assertThatThrownBy(() -> validator.validateAndNormalize(new HyperlinkMessageContent(
                1, 3, "标".repeat(1025), null, null, null,
                List.of(button()), null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1024");
    }

    @Test
    void nonJpegImageIsRejectedEvenWhenStoredMimeClaimsJpeg() {
        fileService.put(11L, new MarketingTemplateFileContent("image/jpeg", new byte[] {1, 2, 3}));
        HyperlinkMessageContent input = new HyperlinkMessageContent(
                1, 1, "标题", "正文", "描述", "https://example.com", List.of(), null, 11L, null);

        assertThatThrownBy(() -> validator.validateAndNormalize(input))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("JPEG")
                .hasMessageContaining("500KB");
    }

    @Test
    void oversizedImageIsRejectedBeforeDecode() {
        fileService.put(11L, new MarketingTemplateFileContent("image/jpeg", new byte[500 * 1024 + 1]));
        HyperlinkMessageContent input = new HyperlinkMessageContent(
                1, 1, "标题", "正文", "描述", "https://example.com", List.of(), null, 11L, null);

        assertThatThrownBy(() -> validator.validateAndNormalize(input))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("JPEG")
                .hasMessageContaining("500KB");
    }

    @Test
    void missingOrCrossTenantImageUsesFrozenNotFoundSemantics() {
        HyperlinkMessageContent input = new HyperlinkMessageContent(
                1, 1, "标题", "正文", "描述", "https://example.com", List.of(), null, 404L, null);

        assertThatThrownBy(() -> validator.validateAndNormalize(input))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ErrorCode.NOT_FOUND.code());
                    assertThat(exception).hasMessage("图片不存在或已删除");
                });
    }

    private static HyperlinkButton button() {
        return new HyperlinkButton(
                HyperlinkButtonType.CTA_URL,
                "  立即查看  ",
                " https://example.com/promo ",
                true,
                1);
    }

    private static byte[] jpegBytes() throws IOException {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpg", output);
            return output.toByteArray();
        }
    }

    private static final class FakeMarketingTemplateFileService implements MarketingTemplateFileService {

        private final Map<Long, MarketingTemplateFileContent> files = new HashMap<>();

        void put(Long id, MarketingTemplateFileContent content) {
            files.put(id, content);
        }

        @Override
        public com.armada.marketing.model.vo.MarketingTemplateFileVO uploadImage(MultipartFile file) {
            throw new UnsupportedOperationException("测试不执行上传");
        }

        @Override
        public MarketingTemplateFileContent content(Long id) {
            MarketingTemplateFileContent content = files.get(id);
            if (content == null) {
                throw new BusinessException(
                        ErrorCode.NOT_FOUND,
                        "营销模板图片不存在");
            }
            return content;
        }
    }
}
