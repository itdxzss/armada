package com.armada.platform.sms.grizzly.model;

import java.util.Optional;

/**
 * 一次订单查询的短信状态快照。
 * @param state 当前收码阶段
 * @param code 本次已收到的验证码，仅 RECEIVED 状态具有值
 * @param previousCode WAIT_RETRY 返回的旧码，不能当作新收到的验证码提交
 */
public record GrizzlySmsStatus(State state, Optional<String> code, Optional<String> previousCode) {

    /** 日志不输出新码和旧码。 */
    @Override
    public String toString() {
        return "GrizzlySmsStatus[state=" + state + ", code=[REDACTED], previousCode=[REDACTED]]";
    }

    /** 平台文档定义的收码状态，保留各个等待阶段的区别。 */
    public enum State {
        /** 等待第一条短信。 */
        WAITING_CODE,
        /** 当前仅有旧码，仍处于等待重试阶段。 */
        WAITING_RETRY,
        /** 平台等待目标应用重新发送短信；查询本身不触发重新发送。 */
        WAITING_RESEND,
        /** 激活已取消。 */
        CANCELLED,
        /** 已收到验证码，目标应用是否接受需由注册链路单独确认。 */
        RECEIVED
    }
}
