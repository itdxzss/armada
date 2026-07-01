package com.armada.resource.controller;

import com.armada.resource.model.dto.IpProxyStatsCountryQuery;
import com.armada.resource.model.dto.IpProxyStatsDetailQuery;
import com.armada.resource.model.vo.IpProxyCountryStatsVO;
import com.armada.resource.model.vo.IpProxyStatsDetailVO;
import com.armada.resource.model.vo.IpProxyStatsSummaryVO;
import com.armada.resource.service.IpProxyStatsService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IP 数据统计菜单接口。
 */
@RestController
@RequestMapping("/api/ip-proxies/stats")
public class IpProxyStatsController {

    private final IpProxyStatsService service;

    public IpProxyStatsController(IpProxyStatsService service) {
        this.service = service;
    }

    /** 顶部统计卡片。 */
    @GetMapping("/summary")
    public ApiResponse<IpProxyStatsSummaryVO> summary() {
        return ApiResponse.ok(service.summary());
    }

    /** 国家/地区维度统计表。 */
    @GetMapping("/countries")
    public ApiResponse<PageResult<IpProxyCountryStatsVO>> countries(@ModelAttribute IpProxyStatsCountryQuery query) {
        return ApiResponse.ok(service.countries(query));
    }

    /** 指定国家/地区下的 IP 明细。 */
    @GetMapping("/countries/{region}/proxies")
    public ApiResponse<PageResult<IpProxyStatsDetailVO>> regionProxies(
            @PathVariable String region,
            @ModelAttribute IpProxyStatsDetailQuery query) {
        return ApiResponse.ok(service.regionProxies(region, query));
    }
}
