package com.armada.marketing.model.vo;

/**
 * 营销模板图片上传结果。
 */
public record MarketingTemplateFileVO(
        Long id,
        String originalFilename,
        String contentType,
        Long sizeBytes,
        String url) {
}
