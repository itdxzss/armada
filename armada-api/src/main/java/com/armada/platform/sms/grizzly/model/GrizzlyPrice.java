package com.armada.platform.sms.grizzly.model;

import java.math.BigDecimal;

/**
 * 某国家和服务的当前价格与库存快照，不保证后续购买一定有库存。
 * @param country 国家目录 ID
 * @param service 服务目录代码
 * @param cost 当前单价；getPrices 未给出币种，不能假定美元
 * @param count 供应商报告的可用数量
 */
public record GrizzlyPrice(String country, String service, BigDecimal cost, long count) {
}
