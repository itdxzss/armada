package com.armada.promotion.template.model.vo;

import java.util.List;

/** 模板管理分页出参，不暴露 tenantId，租户隔离由服务端上下文保证。 */
public record PromotionTemplateVO(
        Long id,
        String templateCode,
        String templateName,
        String previewUri,
        boolean subaccountVisible,
        List<PromotionTemplateSupportedParamVO> supportedParams,
        String remark,
        Long createdAt,
        Long updatedAt) {
}
