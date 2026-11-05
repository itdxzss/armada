package com.armada.account.model.vo;
/** 单账号历史，元信息取最新成功快照。 */
public record AccountTierCheckHistoryVO(Long accountId, String phone, String serverUserType, String phoneCountryCode, Long joinDateMs, Boolean mvSubscribed, java.util.List<AccountTierCheckTimelineEntryVO> entries) {}
