package com.armada.account.model.vo;


/** 租户可见的接码注册数据，不含验证码、六段或执行租约。 */
public record AccountRegistrationCountsVO(int pending, int processing, int succeeded, int failed, int unknown, int cancelled) { }
