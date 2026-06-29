package com.armada.resource.controller;

import com.armada.resource.model.dto.IpProxyBatchDeleteDTO;
import com.armada.resource.model.dto.IpProxyImportDTO;
import com.armada.resource.model.dto.IpProxyQuery;
import com.armada.resource.model.vo.IpProxyImportResultVO;
import com.armada.resource.model.vo.IpProxyVO;
import com.armada.resource.service.IpProxyDeletionService;
import com.armada.resource.service.IpProxyService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IP 管理菜单（resource 域）。只做参数接收、上下文衔接与响应组装，业务规则在 Service。
 */
@RestController
@RequestMapping("/api/ip-proxies")
public class IpProxyController {

    private final IpProxyService service;
    private final IpProxyDeletionService deletionService;

    public IpProxyController(IpProxyService service, IpProxyDeletionService deletionService) {
        this.service = service;
        this.deletionService = deletionService;
    }

    /**
     * 分页查询 IP 代理列表：按国家 / 类型 / 来源 / 关键字组合筛选并分页，供列表页展示。
     *
     * @param query 搜索与分页条件
     * @return 当前页代理及总数
     */
    @GetMapping
    public ApiResponse<PageResult<IpProxyVO>> list(@ModelAttribute IpProxyQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    /**
     * 查询本租户 IP 池已有国家/区域,供筛选框和账号导入选择 IP 国家使用。
     *
     * @return 去重后的国家/区域列表,「混合（不限国家）」优先
     */
    @GetMapping("/regions")
    public ApiResponse<List<String>> regions() {
        return ApiResponse.ok(service.listRegions());
    }

    /**
     * 批量导入 IP 代理：逐行解析 host:port:用户名:密码，去重 + 格式校验后入库，返回导入统计。
     *
     * @param dto 导入参数（国家、协议、来源、多行原文）
     * @return 导入结果
     */
    @PostMapping("/import")
    public ApiResponse<IpProxyImportResultVO> importProxies(@RequestBody IpProxyImportDTO dto) {
        return ApiResponse.ok(service.importProxies(dto));
    }

    /**
     * 批量删除 IP 代理（软删）。
     *
     * @param request 选中的代理 ID 列表
     * @return 空数据成功响应
     */
    @PostMapping("/batch-delete")
    public ApiResponse<Void> batchDelete(@RequestBody IpProxyBatchDeleteDTO request) {
        deletionService.batchDelete(request.ids());
        return ApiResponse.ok();
    }
}
