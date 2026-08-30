package com.armada.hyperlink.task.mapper;

import com.armada.hyperlink.task.model.query.HyperlinkMarketingStatCriteria;
import com.armada.hyperlink.task.model.vo.HyperlinkMarketingStatRow;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 超链市场日/小时投影的真实 SQL Mapper。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface HyperlinkMarketingStatMapper {
    List<HyperlinkMarketingStatRow> selectDaily(HyperlinkMarketingStatCriteria criteria);
    List<HyperlinkMarketingStatRow> selectHourly(HyperlinkMarketingStatCriteria criteria);
    HyperlinkMarketingStatRow selectExactOverview(HyperlinkMarketingStatCriteria criteria);
    List<HyperlinkMarketingStatRow> selectCountries(HyperlinkMarketingStatCriteria criteria);
    List<Long> selectProjectionTenantIds(@Param("startAt") long startAt,
            @Param("endAt") long endAt);
    int upsertDailyBucket(@Param("tenantId") long tenantId, @Param("statDate") int statDate,
            @Param("startAt") long startAt, @Param("endAt") long endAt,
            @Param("now") long now);
    int upsertHourlyBucket(@Param("tenantId") long tenantId,
            @Param("hourStartAt") long hourStartAt, @Param("hourEndAt") long hourEndAt,
            @Param("now") long now);
    int deleteDailyBefore(@Param("statDate") int statDate, @Param("limit") int limit);
    int deleteHourlyBefore(@Param("hourStartAt") long hourStartAt, @Param("limit") int limit);
}
