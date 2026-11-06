package com.armada.account.model.vo;

import java.math.BigDecimal;
import java.util.List;

/** 租户可见的接码注册数据，不含验证码、六段或执行租约。 */
public record AccountRegistrationCatalogVO(String serviceCode, String serviceName, List<Country> countries, boolean orderingEnabled, String disabledReason) {
    /**
     * 供应商国家目录项；ID 不是国际区号。
     * @param id 供应商国家 ID
     * @param name 国家名称
     * @param stock 供应商报告的当前库存；查询失败或无报价时为空
     * @param price 供应商报价（getPrices 未给币种）；无报价时为空
     */
    public record Country(String id, String name, Long stock, BigDecimal price) {
        /** 不带库存信息的目录项。 */
        public Country(String id, String name) { this(id, name, null, null); }
    }
 }
