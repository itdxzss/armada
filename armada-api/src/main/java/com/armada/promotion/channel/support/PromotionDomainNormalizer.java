package com.armada.promotion.channel.support;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.net.IDN;
import java.util.Locale;
import org.springframework.util.StringUtils;

/** 把页面输入统一为可作为唯一键和链接拼接基础的域名主机名。 */
public final class PromotionDomainNormalizer {

    private static final String HTTPS_PREFIX = "https://";

    private PromotionDomainNormalizer() {
    }

    /**
     * 规范化访问域名。
     *
     * <p>允许纯域名或 HTTPS 前缀，输出小写 ASCII/Punycode 主机名；拒绝 HTTP、端口、路径、查询参数和账号信息。</p>
     *
     * @param value 页面输入域名
     * @return 可用于数据库唯一键和链接拼接的规范化主机名
     */
    public static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.VALIDATION, "访问域名不能为空");
        }
        String host = value.trim();
        if (host.regionMatches(true, 0, "http://", 0, "http://".length())) {
            throw new BusinessException(ErrorCode.VALIDATION, "访问域名只支持 HTTPS");
        }
        if (host.regionMatches(true, 0, HTTPS_PREFIX, 0, HTTPS_PREFIX.length())) {
            host = host.substring(HTTPS_PREFIX.length());
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }

        // 域名表只保存 host，禁止把协议、端口、路径或用户信息混入唯一键。
        if (host.contains("://")) {
            throw new BusinessException(ErrorCode.VALIDATION, "访问域名只支持 HTTPS");
        }
        if (host.indexOf(':') >= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "访问域名不能包含端口");
        }
        if (host.indexOf('/') >= 0 || host.indexOf('?') >= 0 || host.indexOf('#') >= 0
                || host.indexOf('@') >= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "只允许填写域名，不能包含路径、参数或账号信息");
        }
        final String ascii;
        try {
            // Unicode 域名统一转换为 Punycode，避免同一域名因不同写法绕过唯一约束。
            ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "访问域名格式不正确");
        }
        if (ascii.length() > 253 || !ascii.contains(".")) {
            throw new BusinessException(ErrorCode.VALIDATION, "访问域名格式不正确");
        }

        // 按 DNS 标签规则再次校验长度和连字符位置。
        for (String label : ascii.split("\\.")) {
            if (label.isEmpty() || label.length() > 63 || label.startsWith("-") || label.endsWith("-")) {
                throw new BusinessException(ErrorCode.VALIDATION, "访问域名格式不正确");
            }
        }
        return ascii;
    }
}
