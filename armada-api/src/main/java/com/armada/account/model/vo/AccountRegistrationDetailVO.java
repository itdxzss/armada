package com.armada.account.model.vo;

import java.util.List;

/** 租户可见的接码注册数据，不含验证码、六段或执行租约。 */
public record AccountRegistrationDetailVO(AccountRegistrationTaskVO task, List<AccountRegistrationItemVO> items) { }
