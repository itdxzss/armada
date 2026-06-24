package com.armada.marketing.controller;

import com.armada.marketing.model.dto.BatchIdsRequest;
import com.armada.marketing.model.dto.MarketingTemplateDTO;
import com.armada.marketing.model.dto.MarketingTemplateQuery;
import com.armada.marketing.model.vo.MarketingTemplateVO;
import com.armada.marketing.service.MarketingTemplateService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 营销模板菜单(素材管理)。只做参数接收、上下文衔接与响应组装,业务规则在 Service。
 */
@RestController
@RequestMapping("/api/marketing-templates")
public class MarketingTemplateController {

    private final MarketingTemplateService service;

    public MarketingTemplateController(MarketingTemplateService service) {
        this.service = service;
    }

    /**
     * 查询营销模板列表:按 ID / 模板名 / 文本类型 / 超链模式组合筛选并分页,供列表页展示。
     *
     * @param query 搜索与分页条件
     * @return 当前页模板及总数
     */
    @GetMapping
    public ApiResponse<PageResult<MarketingTemplateVO>> list(@ModelAttribute MarketingTemplateQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    /**
     * 新增营销模板:接收表单配置,交 Service 校验并落库后返回新模板;校验失败以业务错误码返回。
     *
     * @param dto 模板配置
     * @return 创建后的模板
     */
    @PostMapping
    public ApiResponse<MarketingTemplateVO> create(@RequestBody MarketingTemplateDTO dto) {
        return ApiResponse.ok(service.create(dto));
    }

    /**
     * 编辑指定营销模板,返回更新后的模板。
     *
     * @param id  模板 ID
     * @param dto 新的模板配置
     * @return 更新后的模板
     */
    @PutMapping("/{id}")
    public ApiResponse<MarketingTemplateVO> update(@PathVariable Long id, @RequestBody MarketingTemplateDTO dto) {
        return ApiResponse.ok(service.update(id, dto));
    }

    /**
     * 复制指定营销模板,生成名称带「副本」后缀的新模板。
     *
     * @param id 源模板 ID
     * @return 复制生成的新模板
     */
    @PostMapping("/{id}/clone")
    public ApiResponse<MarketingTemplateVO> clone(@PathVariable Long id) {
        return ApiResponse.ok(service.clone(id));
    }

    /**
     * 批量删除营销模板(软删)。
     *
     * @param request 选中的模板 ID 列表
     * @return 空数据成功响应
     */
    @PostMapping("/batch-delete")
    public ApiResponse<Void> batchDelete(@RequestBody BatchIdsRequest request) {
        service.batchDelete(request.ids());
        return ApiResponse.ok();
    }
}
