package com.armada.account.model.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 超链任务选择发信账号的账号域查询条件。
 *
 * <p>身份、状态、凭据和账号画像均由账号域在同一条 SQL 中下推；画像字段为 NULL 时只在未配置
 * 对应筛选的情况下入选，禁止把未知当 0 或 false。</p>
 */
public record AccountHyperlinkCandidateQuery(
        List<String> countryIso2s,
        List<String> excludeCountryIso2s,
        String continent,
        List<Long> groupIds,
        List<Long> channelIds,
        String protocolId,
        String onlineStatus,
        Integer rotationStatus,
        Integer accountType,
        String platform,
        String widType,
        String importMode,
        Boolean groupInviteAllowed,
        String phone,
        Long importBatchId,
        Integer source,
        Integer friendCountMin,
        Integer friendCountMax,
        BigDecimal retentionDaysMin,
        BigDecimal retentionDaysMax,
        Integer registerDaysMin,
        Integer registerDaysMax,
        Long createdAtFrom,
        Long createdAtTo,
        List<String> privateCapableBackends,
        long observedAt) {
}
