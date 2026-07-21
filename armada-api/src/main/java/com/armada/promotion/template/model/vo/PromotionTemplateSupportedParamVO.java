package com.armada.promotion.template.model.vo;

/**
 * 模板支持参数标签。
 *
 * @param code 稳定程序代码，例如 themeColor
 * @param label 页面展示文案，例如 主题色
 */
public record PromotionTemplateSupportedParamVO(String code, String label) {
}
