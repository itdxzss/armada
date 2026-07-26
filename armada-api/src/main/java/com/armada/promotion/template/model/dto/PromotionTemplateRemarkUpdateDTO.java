package com.armada.promotion.template.model.dto;

/** 模板备注修改参数；备注允许为空，非空时最长 500 个字符。 */
public record PromotionTemplateRemarkUpdateDTO(String remark) {
}
