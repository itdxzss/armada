package com.armada.platform.registration.cobalt.model;

/** Cobalt 已持久化的注册阶段；REGISTERED 与安卓协议 ONLINE 分别验收。 */
public enum CobaltRegistrationState {
    /** 已创建会话，尚未申请短信。 */ QUEUED,
    /** 正在申请短信，不能重复创建会话。 */ REQUESTING_SMS,
    /** 服务端接受请求，等待实际验证码。 */ WAITING_CODE,
    /** 正在向 WhatsApp 提交验证码。 */ VERIFYING,
    /** Cobalt 注册已确认，可导出凭据。 */ REGISTERED,
    /** 注册被明确拒绝或未能完成。 */ FAILED,
    /** 中断后结果需核对，不能自动替换密钥。 */ UNKNOWN,
    /** 本地操作已取消，不代表供应商已退款。 */ CANCELLED
}
