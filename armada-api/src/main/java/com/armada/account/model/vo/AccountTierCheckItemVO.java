package com.armada.account.model.vo;
/** 明细出参隐藏内部协议身份。 */
public record AccountTierCheckItemVO(Long id, Long accountId, String phone, String serverUserType, String trustTier, String prevTrustTier, Long prevCheckedAt, Integer tierChange, Integer status, String reasonCode, Long joinDateMs, String phoneCountryCode, Long checkedAt) {}
