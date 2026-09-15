package com.armada.account.model.vo;

import java.util.List;

/** 租户可见的接码注册数据，不含验证码、六段或执行租约。 */
public record AccountRegistrationCatalogVO(String serviceCode, String serviceName, List<Country> countries, boolean orderingEnabled, String disabledReason) {
    /** 美国供应商目录项；ID 不是国际区号。 */
    public record Country(String id, String name) { }
 }
