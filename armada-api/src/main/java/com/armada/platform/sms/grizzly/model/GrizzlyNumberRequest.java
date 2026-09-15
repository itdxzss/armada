package com.armada.platform.sms.grizzly.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 购买单个服务的接码号码，country 使用供应商国家 ID，不能使用电话区号。
 * @param service 服务目录返回的代码
 * @param country 国家目录 ID 或 any
 * @param maxPrice 本次购买的最高单价，币种沿用供应商账户
 * @param options 可选购买筛选条件，null 表示不筛选
 */
public record GrizzlyNumberRequest(String service, String country, BigDecimal maxPrice, Options options) {

    /**
     * 供应商原生购买筛选条件。
     * @param minPrice 最低单价，null 表示不限制
     * @param providerIds 指定供应商 ID
     * @param exceptProviderIds 排除供应商 ID
     * @param phoneException 排除的国家代码和号码前缀，每项 3 至 6 位数字
     */
    public record Options(BigDecimal minPrice, List<String> providerIds,
                          List<String> exceptProviderIds, List<String> phoneException) {
        /** 为调用期间的参数建立不可变快照，具体业务校验由客户端统一抛业务异常。 */
        public Options {
            providerIds = snapshot(providerIds);
            exceptProviderIds = snapshot(exceptProviderIds);
            phoneException = snapshot(phoneException);
        }

        private static List<String> snapshot(List<String> values) {
            return values == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(values));
        }
    }
}
