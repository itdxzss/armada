package com.armada.admin.controller;

import com.armada.platform.country.model.vo.CountryOptionsVO;
import com.armada.platform.country.service.CountryService;
import com.armada.shared.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台国家/地区主数据只读接口。当前无角色菜单权限体系,先复用现有 /api/** 租户头登录态。
 */
@RestController
@RequestMapping("/api/admin/countries")
public class CountryController {

    private final CountryService service;

    public CountryController(CountryService service) {
        this.service = service;
    }

    /**
     * 国家下拉选项。scope=ip 时返回 MIXED 虚拟项 + 启用且 IP 可展示的真实国家。
     *
     * @param scope 选项范围,当前支持 ip;为空时按 ip 处理
     * @return 国家选项列表
     */
    @GetMapping("/options")
    public ApiResponse<CountryOptionsVO> options(@RequestParam(name = "scope", defaultValue = "ip") String scope) {
        return ApiResponse.ok(service.options(scope));
    }
}
