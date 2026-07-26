package com.armada.promotion.channel.controller;

import com.armada.promotion.channel.model.vo.PromotionChannelRuntimeVO;
import com.armada.promotion.channel.service.PromotionChannelService;
import com.armada.shared.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 落地页无需登录即可读取的渠道运行时配置接口。 */
@RestController
@RequestMapping("/api/public/promotion-channels")
public class PromotionChannelPublicController {

    private final PromotionChannelService service;

    public PromotionChannelPublicController(PromotionChannelService service) {
        this.service = service;
    }

    /**
     * 按推广码和 Nginx 转发的访问域名查询并校验渠道。
     *
     * @param channelCode 推广链接中的公开短码
     * @param forwardedHost Nginx 使用 {@code $host} 写入的访问域名
     * @return 模板、主题色、下载开关和国家配置
     */
    @GetMapping("/runtime/{channelCode}")
    public ApiResponse<PromotionChannelRuntimeVO> runtime(
            @PathVariable String channelCode,
            @RequestHeader(name = "X-Forwarded-Host", required = false) String forwardedHost) {
        return ApiResponse.ok(service.runtime(channelCode, forwardedHost));
    }
}
