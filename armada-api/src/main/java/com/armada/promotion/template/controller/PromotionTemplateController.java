package com.armada.promotion.template.controller;

import com.armada.promotion.template.model.dto.PromotionTemplateQuery;
import com.armada.promotion.template.model.dto.PromotionTemplateRemarkUpdateDTO;
import com.armada.promotion.template.model.vo.PromotionTemplateVO;
import com.armada.promotion.template.service.PromotionTemplateService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 模板管理分页与备注修改接口。 */
@RestController
@RequestMapping("/api/promotion-templates")
public class PromotionTemplateController {

    private final PromotionTemplateService service;

    public PromotionTemplateController(PromotionTemplateService service) {
        this.service = service;
    }

    /**
     * 分页查询当前租户的有效模板。
     *
     * @param query 页码和每页条数，不包含 tenantId
     * @return 模板统一分页结果
     */
    @GetMapping("/query")
    public ApiResponse<PageResult<PromotionTemplateVO>> page(@ModelAttribute PromotionTemplateQuery query) {
        return ApiResponse.ok(service.page(query));
    }

    /**
     * 修改当前租户内有效模板的备注。
     *
     * @param id 模板 ID
     * @param request 备注参数，允许传空值清空备注
     * @return 统一空成功响应；页面可重新分页查询最新备注和更新时间
     */
    @PatchMapping("/{id}/remark")
    public ApiResponse<Void> updateRemark(
            @PathVariable Long id,
            @RequestBody PromotionTemplateRemarkUpdateDTO request) {
        service.updateRemark(id, request);
        return ApiResponse.ok();
    }
}
