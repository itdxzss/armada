package com.armada.hyperlink.task.model.vo;

/** 市场分析固定十二字段指标对象；比率由原始计数现算。 */
public record HyperlinkMarketingMetricVO(
        String statTime,
        long sendTotal,
        long successNum,
        double sendSuccessRate,
        long deliveredNum,
        double deliveryRate,
        long usedAccountCount,
        long bannedAccountCount,
        double marketingBanRate,
        double avgSendPerAccount,
        long clickUvNum,
        Long updatedAt) {
}
