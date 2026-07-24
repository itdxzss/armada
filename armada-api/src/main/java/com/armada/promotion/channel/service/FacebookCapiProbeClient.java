package com.armada.promotion.channel.service;

/** Facebook CAPI 测试事件出站能力；实现不得记录 Access Token 或平台原始响应。 */
public interface FacebookCapiProbeClient {

    /**
     * 向 Meta 发送单条测试事件。
     *
     * @param command 已完成业务校验的测试事件命令
     * @return 成功或脱敏失败结果
     */
    Result probe(Command command);

    /**
     * Facebook 测试事件命令。
     *
     * @param trackingId Pixel ID
     * @param accessToken 仅本次调用使用的 Token 明文
     * @param testEventCode Meta 测试事件码
     * @param eventSourceUrl 渠道访问地址
     * @param eventName 测试事件名
     * @param eventId 本次事件幂等 ID
     * @param eventTimeSeconds 事件发生时间，epoch 秒
     * @param externalId 合成并哈希后的探测用户标识
     */
    record Command(
            String trackingId,
            String accessToken,
            String testEventCode,
            String eventSourceUrl,
            String eventName,
            String eventId,
            long eventTimeSeconds,
            String externalId) {

        /** 避免调试、断言或异常上下文通过 record 默认 toString 泄露 Token。 */
        @Override
        public String toString() {
            return "Command[trackingId=" + trackingId
                    + ", accessToken=<redacted>, testEventCode=<redacted>"
                    + ", eventSourceUrl=" + eventSourceUrl
                    + ", eventName=" + eventName
                    + ", eventId=" + eventId
                    + ", eventTimeSeconds=" + eventTimeSeconds
                    + ", externalId=" + externalId + "]";
        }
    }

    /**
     * Facebook 调用结果。
     *
     * @param success Meta 是否接受测试事件
     * @param errorCode 稳定脱敏错误码
     * @param errorMessage 面向运营的脱敏错误摘要
     */
    record Result(boolean success, String errorCode, String errorMessage) {
    }
}
