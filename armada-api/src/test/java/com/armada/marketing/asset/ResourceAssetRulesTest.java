package com.armada.marketing.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.marketing.asset.service.ResourceAssetImageValidator;
import com.armada.marketing.asset.service.ResourceAssetTagNormalizer;
import com.armada.shared.exception.BusinessException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** 素材上传图片和标签冻结规则测试。 */
class ResourceAssetRulesTest {

    @Test
    void tagsTrimDropEmptyDeduplicateExactlyAndKeepCase() {
        assertThat(ResourceAssetTagNormalizer.normalize(List.of(" Promo ", "promo", "Promo", " ")))
                .containsExactly("Promo", "promo");
    }

    @Test
    void twentyOneDistinctTagsAreRejectedAfterDeduplication() {
        List<String> tags = IntStream.range(0, 21).mapToObj(index -> "tag-" + index).toList();

        assertThatThrownBy(() -> ResourceAssetTagNormalizer.normalize(tags))
                .isInstanceOf(BusinessException.class)
                .hasMessage("每个素材最多设置 20 个标签");
    }

    @Test
    void uploadRequiresMatchingExtensionMimeMagicAndDecodableJpeg() throws Exception {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        byte[] bytes;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "jpg", output);
            bytes = output.toByteArray();
        }

        var dimensions = ResourceAssetImageValidator.validateUpload("promo.JPG", "image/jpeg", bytes);

        assertThat(dimensions.width()).isEqualTo(3);
        assertThat(dimensions.height()).isEqualTo(2);
        assertThatThrownBy(() -> ResourceAssetImageValidator.validateUpload(
                "promo.png", "image/jpeg", bytes))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("JPEG");
        assertThatThrownBy(() -> ResourceAssetImageValidator.validateUpload(
                "promo.jpg", "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("JPEG");
    }
}
