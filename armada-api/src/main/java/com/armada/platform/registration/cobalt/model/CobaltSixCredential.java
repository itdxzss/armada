package com.armada.platform.registration.cobalt.model;

/**
 * 仅供服务端导入，不作为注册任务接口的响应。
 * @param registrationId 凭据所属会话
 * @param phoneNumber 凭据所属号码
 * @param format 明确的适配格式 zhuan-six-v1
 * @param sixLine 包含密钥的六段数据，只传给已有账号凭据存储
 */
public record CobaltSixCredential(String registrationId, String phoneNumber, String format, String sixLine) {
    /** 防止凭据被日志、错误上下文或调试输出展开。 */
    @Override public String toString() { return "CobaltSixCredential[REDACTED]"; }
}
