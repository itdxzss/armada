package com.armada.promotion.template.controller;

import com.armada.promotion.template.model.dto.PromotionTemplateQuery;
import com.armada.promotion.template.model.vo.PromotionTemplateVO;
import com.armada.promotion.template.service.PromotionTemplateService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 模板管理分页接口。 */
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
}
