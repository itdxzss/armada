package com.armada.platform.registration.cobalt;

/** Cobalt 客户端固定故障分类，禁止透传原始服务端错误文本。 */
public enum CobaltRegistrationFailure {
    /** 本机未启用或未配置密钥。 */ DISABLED,
    /** 服务端未找到该稳定会话。 */ NOT_FOUND,
    /** 无可用注册容量或操作状态冲突。 */ BUSY,
    /** 服务端拒绝请求。 */ REJECTED,
    /** 请求连接或读取失败。 */ TRANSPORT,
    /** 响应结构、会话或凭据格式不符合合同。 */ INVALID_RESPONSE
}
