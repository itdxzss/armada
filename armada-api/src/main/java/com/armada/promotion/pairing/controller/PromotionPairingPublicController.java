package com.armada.promotion.pairing.controller;

import com.armada.promotion.pairing.model.command.PromotionPairingCreateCommand;
import com.armada.promotion.pairing.model.dto.PromotionPairingCreateDTO;
import com.armada.promotion.pairing.model.vo.PromotionPairingCreatedVO;
import com.armada.promotion.pairing.model.vo.PromotionPairingStatusVO;
import com.armada.promotion.pairing.service.PromotionPairingService;
import com.armada.shared.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 无需登录的推广落地页配对会话接口。 */
@RestController
@RequestMapping("/api/public")
public class PromotionPairingPublicController {

    private final PromotionPairingService service;

    public PromotionPairingPublicController(PromotionPairingService service) {
        this.service = service;
    }

    /**
     * 校验推广码与实际访问域名后，发起一次由协议层生成随机码的手机号配对。
     *
     * @param channelCode URL 中的推广码
     * @param forwardedHost 由可信 Nginx 覆盖写入的实际访问域名
     * @param request 手机号配对参数
     * @param servletRequest Servlet 请求，用于读取由可信 Nginx 写入的客户端信息
     * @param response Servlet 响应，用于禁止浏览器和代理缓存敏感状态
     * @return 一次性会话令牌、初始状态和过期时间
     */
    @PostMapping("/promotion-channels/{channelCode}/pairing-sessions")
    public ApiResponse<PromotionPairingCreatedVO> create(
            @PathVariable String channelCode,
            @RequestHeader(name = "X-Forwarded-Host", required = false) String forwardedHost,
            @RequestBody PromotionPairingCreateDTO request,
            HttpServletRequest servletRequest,
            HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return ApiResponse.ok(service.create(new PromotionPairingCreateCommand(
                channelCode,
                forwardedHost,
                request == null ? null : request.phone(),
                request == null ? null : request.fbp(),
                request == null ? null : request.fbc(),
                request == null ? null : request.sourceUrl(),
                resolveClientIp(servletRequest),
                servletRequest.getHeader(HttpHeaders.USER_AGENT))));
    }

    /**
     * 使用专用请求头中的一次性令牌查询配对码生成、等待确认、成功或失败状态。
     *
     * @param sessionToken 创建接口返回的一次性会话令牌
     * @param response Servlet 响应，用于禁止浏览器和代理缓存配对状态
     * @return 当前配对会话的最小公开状态
     */
    @GetMapping("/promotion-pairing-sessions/status")
    public ApiResponse<PromotionPairingStatusVO> status(
            @RequestHeader("X-Pairing-Session-Token") String sessionToken,
            HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        return ApiResponse.ok(service.status(sessionToken));
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        String realIp = request.getHeader("X-Real-IP");
        return isTrustedProxyAddress(remoteAddress) && realIp != null && !realIp.isBlank()
                ? realIp
                : remoteAddress;
    }

    private static boolean isTrustedProxyAddress(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if ("::1".equals(normalized) || normalized.startsWith("fc") || normalized.startsWith("fd")
                || normalized.startsWith("fe80:")) {
            return true;
        }
        String[] parts = normalized.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        try {
            int[] octets = new int[4];
            for (int index = 0; index < parts.length; index++) {
                octets[index] = Integer.parseInt(parts[index]);
                if (octets[index] < 0 || octets[index] > 255) {
                    return false;
                }
            }
            int first = octets[0];
            int second = octets[1];
            return first == 10 || first == 127
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168);
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
