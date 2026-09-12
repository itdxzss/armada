package com.armada.marketing.controller;

import com.armada.marketing.asset.model.enums.ResourceAssetScope;
import com.armada.marketing.model.dto.MarketingTemplateDTO;
import com.armada.marketing.model.dto.MarketingTemplateQuery;
import com.armada.marketing.model.vo.MarketingTemplateVO;
import com.armada.marketing.service.MarketingTemplateService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 剧本素材复用同一消息模板与公共图片，不按营销业务复制文件表。 */
@RestController
@RequestMapping("/api/script-materials")
@PreAuthorize("hasAuthority('tenant:script_marketing:view')")
public class ScriptMaterialController {
    private final MarketingTemplateService service;
    /** 注入现有公共消息模板服务，保持原数据范围及图片引用保护。 */
    public ScriptMaterialController(MarketingTemplateService service) { this.service = service; }
    /** 按文字、图片或按钮消息类型搜索公共消息素材。 */
    @GetMapping public ApiResponse<PageResult<MarketingTemplateVO>> list(@ModelAttribute MarketingTemplateQuery query) {
        return ApiResponse.ok(service.list(query));
    }
    /** 新建完整消息素材，图片只保存公共文件引用。 */
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:script_marketing:view') and hasAuthority('tenant:script_marketing:create')")
    public ApiResponse<MarketingTemplateVO> create(@RequestBody MarketingTemplateDTO dto) { return ApiResponse.ok(service.create(dto, ResourceAssetScope.SCRIPT)); }
    /** 更新素材，仅新选用的剧本会读取新内容，已复制的剧本保持快照。 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('tenant:script_marketing:view') and hasAuthority('tenant:script_marketing:edit')")
    public ApiResponse<MarketingTemplateVO> update(@PathVariable Long id, @RequestBody MarketingTemplateDTO dto) {
        return ApiResponse.ok(service.update(id, dto, ResourceAssetScope.SCRIPT));
    }
    /** 复制为一条新的公共消息素材。 */
    @PostMapping("/{id}/clone")
    @PreAuthorize("hasAuthority('tenant:script_marketing:view') and hasAuthority('tenant:script_marketing:create')")
    public ApiResponse<MarketingTemplateVO> copy(@PathVariable Long id) { return ApiResponse.ok(service.clone(id, ResourceAssetScope.SCRIPT)); }
}
