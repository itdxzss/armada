package com.armada.platform.wbpro;

/**
 * WB 集成接口调用失败。
 *
 * <p>code 是 WB 返回的业务错误码（如 SHADOW_LIMIT_REACHED），网络失败为 WB_UNAVAILABLE，
 * 响应环境与配置不一致为 WB_ENV_MISMATCH，响应无法识别为 WB_BAD_RESPONSE。</p>
 */
public class WbproIntegrationException extends RuntimeException {

    /** 网络失败或超时，结果未知。 */
    public static final String UNAVAILABLE = "WB_UNAVAILABLE";
    /** 响应 envId 与配置的目标不一致。 */
    public static final String ENV_MISMATCH = "WB_ENV_MISMATCH";
    /** 响应结构无法识别。 */
    public static final String BAD_RESPONSE = "WB_BAD_RESPONSE";
    /** WB 未找到该配对（Redis 记录已过期）。 */
    public static final String PAIRING_NOT_FOUND = "PAIRING_NOT_FOUND";

    private final String code;
    private final int httpStatus;

    public WbproIntegrationException(String code, int httpStatus) {
        super("WB integration failed: " + code);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
