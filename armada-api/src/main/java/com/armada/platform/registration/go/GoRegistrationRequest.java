package com.armada.platform.registration.go;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 首次发码冻结的参数，重放时必须完全一致；不传设备配置，由 Go 自动生成并随事务冻结。
 * @param phone 带加号的国际号码
 * @param proxy 含凭据的代理连接串，仅在服务间传输
 * @param isBusiness 是否注册 WhatsApp Business 手机客户端账号
 */
public record GoRegistrationRequest(String phone, String proxy, @JsonProperty("is_business") boolean isBusiness) {
    /** 防止日志展开号码与代理凭据。 */
    @Override public String toString() { return "GoRegistrationRequest{redacted}"; }
}
