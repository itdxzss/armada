package com.armada.account.model.vo;

import java.math.BigDecimal;
import java.util.List;

/** 同一许可的实时商家选项，客户端必须核对请求、国家及价格后确认。 */
public record DeviceRegistrationOptionsVO(String requestId, String countryId, BigDecimal unitPrice,
        List<String> providerIds) { }
