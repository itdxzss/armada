package com.armada.account.model.enums;

import java.util.Arrays;
import java.util.List;

/** 接码订单 Go 注册办理的状态；只描述办理过程，接码订单自身状态见 {@link SmsActivationState}。 */
public enum SmsRegistrationState {
    /** 已点击开始并占好代理，正在让 Go 发验证码；每轮用同一把键重放，直到拿到明确结果。 */
    REQUESTING(1),
    /** Go 已确认发码，等待号商出码。 */
    WAITING_CODE(2),
    /** 号商验证码已冻结，正在让 Go 验码；每轮用同一把键、同一个验证码和版本重放。 */
    VERIFYING(3),
    /** Go 返回 REGISTERED 且注册结果可用；流程结束，归还代理池。 */
    REGISTERED(4),
    /** 失败或结果不明，停止注册并归还代理池，原因见 {@link SmsRegistrationFailure}，由运营用旧按钮处理订单。 */
    ACTION_REQUIRED(5);

    /** 数据库存储值。 */
    private final int code;

    SmsRegistrationState(int code) { this.code = code; }

    /** @return 数据库存储值 */
    public int code() { return code; }

    /** @return 是否仍由后台任务推进；此时订单被占用，旧的完成/取消接口必须拒绝 */
    public boolean isActive() { return this == REQUESTING || this == WAITING_CODE || this == VERIFYING; }

    /** @return 所有“后台推进中”状态的数据库值，供 SQL 参数使用，避免在 XML 里写魔法值 */
    public static List<Integer> activeCodes() {
        return Arrays.stream(values()).filter(SmsRegistrationState::isActive).map(SmsRegistrationState::code).toList();
    }

    /** @return 已结束注册的状态值，这些记录只需补偿尚未完成的代理释放 */
    public static List<Integer> finishedCodes() {
        return List.of(REGISTERED.code(), ACTION_REQUIRED.code());
    }

    /** @param code 数据库值 @return 对应办理状态 */
    public static SmsRegistrationState fromCode(int code) {
        for (var state : values()) { if (state.code == code) { return state; } }
        throw new IllegalArgumentException("Invalid sms registration state");
    }
}
