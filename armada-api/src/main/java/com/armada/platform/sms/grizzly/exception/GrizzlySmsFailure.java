package com.armada.platform.sms.grizzly.exception;

/** 接码客户端固定错误分类；禁止将供应商响应正文用作错误码或消息。 */
public enum GrizzlySmsFailure {
    /** 接码集成尚未启用。 */
    DISABLED,
    /** 服务端未配置 API 密钥。 */
    CREDENTIALS_MISSING,
    /** 购买及激活状态变更尚未启用。 */
    MUTATIONS_DISABLED,
    /** 供应商拒绝 API 密钥。 */
    BAD_KEY,
    /** 供应商报告未收到 API 密钥。 */
    NO_KEY,
    /** 供应商账户余额不足。 */
    NO_BALANCE,
    /** 所选服务或地区没有号码库存。 */
    NO_NUMBERS,
    /** 供应商不支持请求的操作。 */
    BAD_ACTION,
    /** 服务代码不存在或不适用。 */
    BAD_SERVICE,
    /** 激活状态变更不被接受。 */
    BAD_STATUS,
    /** 供应商找不到激活订单。 */
    NO_ACTIVATION,
    /** 供应商限制当前访问地区。 */
    REGION_RESTRICTED,
    /** 供应商停止销售该服务号码。 */
    SERVICE_PROHIBITED,
    /** 供应商报告内部错误，写操作结果需要核对。 */
    PROVIDER_ERROR,
    /** 供应商返回非成功 HTTP 状态。 */
    HTTP_ERROR,
    /** 请求在网络或传输阶段失败。 */
    TRANSPORT_ERROR,
    /** 响应不满足已核对的供应商契约。 */
    INVALID_RESPONSE
}
