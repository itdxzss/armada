package com.armada.promotion.channel.controller;

import com.armada.promotion.channel.model.dto.PromotionChannelCreateDTO;
import com.armada.promotion.channel.model.dto.PromotionChannelQuery;
import com.armada.promotion.channel.model.vo.PromotionChannelVO;
import com.armada.promotion.channel.service.PromotionChannelService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 渠道管理新增和分页接口。 */
@RestController
@RequestMapping("/api/promotion-channels")
public class PromotionChannelController {

    private final PromotionChannelService service;

    public PromotionChannelController(PromotionChannelService service) {
        this.service = service;
    }

    /**
     * 新增推广渠道。
     *
     * <p>Controller 只负责接收入参和包装统一响应；模板、国家、域名及 Token 等业务校验均由 Service 完成。</p>
     *
     * @param request 渠道新增参数，不包含 tenantId
     * @return 新增后的渠道详情，响应中不会包含 Access Token
     */
    @PostMapping
    public ApiResponse<PromotionChannelVO> create(@RequestBody PromotionChannelCreateDTO request) {
        return ApiResponse.ok(service.create(request));
    }

    /**
     * 分页查询推广渠道。
     *
     * @param query 页面筛选与分页参数
     * @return 统一分页结果
     */
    @GetMapping
    public ApiResponse<PageResult<PromotionChannelVO>> page(@ModelAttribute PromotionChannelQuery query) {
        return ApiResponse.ok(service.page(query));
    }
}
