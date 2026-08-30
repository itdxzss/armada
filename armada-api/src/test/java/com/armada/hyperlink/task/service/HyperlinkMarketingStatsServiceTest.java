package com.armada.hyperlink.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.hyperlink.task.mapper.HyperlinkMarketingStatMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkMarketingStatsQuery;
import com.armada.hyperlink.task.model.query.HyperlinkMarketingStatCriteria;
import com.armada.hyperlink.task.model.vo.HyperlinkMarketingStatRow;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HyperlinkMarketingStatsServiceTest {
    private final HyperlinkMarketingStatMapper mapper = mock(HyperlinkMarketingStatMapper.class);
    private final HyperlinkMarketingStatsService service = new HyperlinkMarketingStatsService(mapper);

    @BeforeEach
    void setTenant() { TenantContext.set(7L); }

    @AfterEach
    void clearTenant() { TenantContext.clear(); }

    @Test
    void dailySummaryUsesExactOverviewAndCalculatesRatios() {
        when(mapper.selectDaily(any())).thenReturn(java.util.List.of(
                row(20260829L, 10, 8, 4, 2, 1, 3, 100L),
                row(20260830L, 20, 10, 5, 3, 2, 4, 200L)));
        when(mapper.selectExactOverview(any()))
                .thenReturn(row(null, 30, 18, 9, 3, 1, 7, 300L));

        var result = service.stats(dayQuery());

        assertThat(result.granularity()).isEqualTo("day");
        assertThat(result.items()).hasSize(1);
        var item = result.items().get(0);
        assertThat(item.series()).extracting(value -> value.statTime())
                .containsExactly("2026-08-29", "2026-08-30");
        assertThat(item.summary().sendTotal()).isEqualTo(30);
        assertThat(item.summary().usedAccountCount()).isEqualTo(5);
        assertThat(item.summary().bannedAccountCount()).isEqualTo(3);
        assertThat(item.summary().clickUvNum()).isEqualTo(7);
        assertThat(item.summary().sendSuccessRate()).isEqualTo(0.6);
        assertThat(item.summary().deliveryRate()).isEqualTo(0.5);
        assertThat(item.summary().avgSendPerAccount()).isEqualTo(3.6);
        assertThat(item.summary().updatedAt()).isEqualTo(200L);
        assertThat(result.overview().sendTotal()).isEqualTo(30);
        assertThat(result.overview().usedAccountCount()).isEqualTo(3);
        assertThat(result.overview().bannedAccountCount()).isEqualTo(1);
        assertThat(result.overview().avgSendPerAccount()).isEqualTo(6.0);
        assertThat(result.overview().updatedAt()).isEqualTo(300L);
    }

    @Test
    void deviceOsMapsAndroidAndCountriesExcludeUnknown() {
        HyperlinkMarketingStatsQuery query = dayQuery();
        query.setDeviceOs("android");
        when(mapper.selectExactOverview(any())).thenReturn(row(null, 0, 0, 0, 0, 0, 0, 0));
        service.stats(query);
        when(mapper.selectCountries(any())).thenReturn(java.util.List.of(
                countryRow("BR", "US"), countryRow("ZZ", "CN")));

        var result = service.countries(dayQuery());

        assertThat(result.senderCountryIso2()).containsExactly("BR");
        assertThat(result.recipientCountryIso2()).containsExactly("CN", "US");
        ArgumentCaptor<HyperlinkMarketingStatCriteria> criteria =
                ArgumentCaptor.forClass(HyperlinkMarketingStatCriteria.class);
        verify(mapper).selectDaily(criteria.capture());
        assertThat(criteria.getValue().senderDeviceOs()).isEqualTo(1);
        assertThat(criteria.getValue().tenantId()).isEqualTo(7L);
        verify(mapper).selectCountries(criteria.capture());
        assertThat(criteria.getValue().statDateFrom()).isEqualTo(20260829);
        assertThat(criteria.getValue().statDateTo()).isEqualTo(20260830);
    }

    @Test
    void rejectsWindowsBeyondFrozenLimits() {
        HyperlinkMarketingStatsQuery day = dayQuery();
        day.setDateFrom("2026-01-01");
        day.setDateTo("2026-04-01");
        assertThatThrownBy(() -> service.stats(day))
                .isInstanceOf(BusinessException.class).hasMessageContaining("90 天");

        HyperlinkMarketingStatsQuery hour = hourQuery();
        hour.setDateFrom("2026-08-01 00:00:00");
        hour.setDateTo("2026-08-08 00:00:00");
        assertThatThrownBy(() -> service.stats(hour))
                .isInstanceOf(BusinessException.class).hasMessageContaining("7 天");

        hour.setDateTo("2026-08-07 23:59:59");
        when(mapper.selectHourly(any())).thenReturn(java.util.List.of());
        when(mapper.selectExactOverview(any())).thenReturn(row(null, 0, 0, 0, 0, 0, 0, 0));
        assertThat(service.stats(hour).granularity()).isEqualTo("hour");
    }

    @Test
    void rejectsInvalidHourlyCalendarDateInsteadOfNormalizingIt() {
        HyperlinkMarketingStatsQuery hour = hourQuery();
        hour.setDateFrom("2026-02-30 01:00:00");
        hour.setDateTo("2026-03-01 01:00:00");

        assertThatThrownBy(() -> service.stats(hour))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("yyyy-MM-dd HH:mm:ss");
    }

    private static HyperlinkMarketingStatsQuery dayQuery() {
        HyperlinkMarketingStatsQuery query = new HyperlinkMarketingStatsQuery();
        query.setDateFrom("2026-08-29");
        query.setDateTo("2026-08-30");
        query.setGranularity("day");
        return query;
    }

    private static HyperlinkMarketingStatsQuery hourQuery() {
        HyperlinkMarketingStatsQuery query = new HyperlinkMarketingStatsQuery();
        query.setGranularity("hour");
        return query;
    }

    private static HyperlinkMarketingStatRow row(Long time, long send, long success,
            long delivered, long used, long banned, long clicks, long updated) {
        HyperlinkMarketingStatRow row = countryRow("BR", "US");
        row.setStatTime(time);
        row.setSendTotal(send);
        row.setSuccessNum(success);
        row.setDeliveredNum(delivered);
        row.setUsedAccountCount(used);
        row.setBannedAccountCount(banned);
        row.setClickUvNum(clicks);
        row.setUpdatedAt(updated);
        return row;
    }

    private static HyperlinkMarketingStatRow countryRow(String sender, String recipient) {
        HyperlinkMarketingStatRow row = new HyperlinkMarketingStatRow();
        row.setSenderCountryIso2(sender);
        row.setRecipientCountryIso2(recipient);
        return row;
    }
}
