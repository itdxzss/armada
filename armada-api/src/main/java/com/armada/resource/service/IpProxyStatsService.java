package com.armada.resource.service;

import com.armada.resource.model.dto.IpProxyStatsCountryQuery;
import com.armada.resource.model.dto.IpProxyStatsDetailQuery;
import com.armada.resource.model.vo.IpProxyCountryStatsVO;
import com.armada.resource.model.vo.IpProxyStatsDetailVO;
import com.armada.resource.model.vo.IpProxyStatsSummaryVO;
import com.armada.shared.response.PageResult;

/**
 * IP 数据统计只读业务边界。
 */
public interface IpProxyStatsService {

    /** 查询总览统计。 */
    IpProxyStatsSummaryVO summary();

    /** 分页查询国家/地区维度统计。 */
    PageResult<IpProxyCountryStatsVO> countries(IpProxyStatsCountryQuery query);

    /** 分页查询指定国家/地区下的 IP 明细。 */
    PageResult<IpProxyStatsDetailVO> regionProxies(String region, IpProxyStatsDetailQuery query);
}
