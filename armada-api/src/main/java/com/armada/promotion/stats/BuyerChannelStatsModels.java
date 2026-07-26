package com.armada.promotion.stats;

import java.math.BigDecimal;
import java.util.List;

/** 渠道统计接口使用的轻量数据模型。 */
public final class BuyerChannelStatsModels {

    private BuyerChannelStatsModels() {
    }

    public record Option(Long id, String name) { }

    public record CountryOption(String code, String name) { }

    public record Options(List<Option> channels, List<Option> templates,
                          List<CountryOption> countries, List<Option> creators,
                          List<Option> parentUsers) { }

    public record Query(String dateStart, String dateEnd, Long channelId,
                        String channelName, Long templateId, String countryCode,
                        Long createdBy, Long parentUserId, String sortField,
                        String sortOrder) { }

    public record DailyInput(String countryCode, String dateStart, String dateEnd,
                             BigDecimal spend, Long impressions, Long clicks,
                             BigDecimal serviceRate, BigDecimal otherFee, Integer version) { }

    public record StatsRow(Long channelId, String channelName, String channelCode,
                           String countryCode, String countryName, Long templateId,
                           String templateName, BigDecimal spend, long impressions,
                           long clicks, BigDecimal serviceRate, BigDecimal otherFee,
                           long uv, long visitDurationSeconds, long loginRequestCount,
                           long loginRequestUserCount, long loginSuccessCount,
                           long loginSuccessUserCount, long unbindCount,
                           BigDecimal clickRate, BigDecimal serviceFee, BigDecimal totalFee,
                           BigDecimal loginRequestRate, BigDecimal loginSuccessRate,
                           BigDecimal visitorConversionRate, BigDecimal unbindRate,
                           BigDecimal accountCost) { }

    public record DailyRow(String date, String countryCode, BigDecimal spend,
                           long impressions, long clicks, BigDecimal serviceRate,
                           BigDecimal otherFee, long uv, long visitDurationSeconds,
                           long loginRequestCount, long loginRequestUserCount,
                           long loginSuccessCount, long loginSuccessUserCount,
                           long unbindCount, BigDecimal clickRate, BigDecimal serviceFee,
                           BigDecimal totalFee, BigDecimal loginRequestRate,
                           BigDecimal loginSuccessRate, BigDecimal visitorConversionRate,
                           BigDecimal unbindRate, BigDecimal accountCost, int version) { }

    public record UpdateResult(DailyRow daily, StatsRow summary) { }
}
