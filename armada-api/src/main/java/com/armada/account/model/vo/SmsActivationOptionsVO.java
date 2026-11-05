package com.armada.account.model.vo;

import java.math.BigDecimal;
import java.util.List;

/**
 * 手动接码页面初始化信息。
 * @param serviceCode WhatsApp 服务代码
 * @param countries 当前开放的美国目录项
 * @param balance 平台余额；查询失败或未启用时为空
 * @param purchaseEnabled 是否允许取号
 * @param disabledReason 不允许取号的固定原因码
 * @param registrationEnabled 当前租户是否可以对订单点“开始注册”
 * @param registrationDisabledReason 不允许开始注册的固定原因码，可用时为空串
 */
public record SmsActivationOptionsVO(String serviceCode, List<AccountRegistrationCatalogVO.Country> countries,
        BigDecimal balance, boolean purchaseEnabled, String disabledReason, boolean registrationEnabled,
        String registrationDisabledReason) { }
