package com.armada.promotion.channel.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/** 渠道推广平台。 */
public enum PromotionPlatform {
    FACEBOOK(1, "Facebook", true),
    TIKTOK(2, "TikTok", true),
    KUAISHOU(3, "快手", false),
    MGSKY_ADS(4, "MGSKY Ads", false);

    private final int code;
    private final String label;
    private final boolean capiSupported;

    PromotionPlatform(int code, String label, boolean capiSupported) {
        this.code = code;
        this.label = label;
        this.capiSupported = capiSupported;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    public boolean capiSupported() {
        return capiSupported;
    }

    /**
     * 按数据库平台码解析枚举，不支持的值直接抛业务校验异常。
     *
     * @param code 平台码
     * @return 推广平台枚举
     */
    public static PromotionPlatform require(Integer code) {
        if (code != null) {
            for (PromotionPlatform platform : values()) {
                if (platform.code == code) {
                    return platform;
                }
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "推广平台必须是 1(Facebook)、2(TikTok)、3(快手)或4(MGSKY Ads)");
    }

    /** 根据平台码返回页面展示名称；历史未知值返回 UNKNOWN。 */
    public static String labelOf(Integer code) {
        if (code == null) {
            return null;
        }
        for (PromotionPlatform platform : values()) {
            if (platform.code == code) {
                return platform.label;
            }
        }
        return "UNKNOWN";
    }
}
