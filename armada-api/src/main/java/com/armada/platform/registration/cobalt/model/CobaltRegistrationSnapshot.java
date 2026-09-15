package com.armada.platform.registration.cobalt.model;

import java.util.Optional;

/**
 * 注册会话快照，仅在服务端任务内部消费。
 * @param registrationId 稳定幂等会话 ID
 * @param phoneNumber 注册号码
 * @param state 已持久化阶段
 * @param reason 脱敏原因分类
 */
public record CobaltRegistrationSnapshot(String registrationId, String phoneNumber,
                                         CobaltRegistrationState state, Optional<String> reason) {
    /** 不通过 record 默认输出泄露号码。 */
    @Override public String toString() {
        return "CobaltRegistrationSnapshot[id=" + registrationId + ", state=" + state + "]";
    }
}
