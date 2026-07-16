package com.armada.group.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/** WhatsApp 群限时消息模式。 */
public enum GroupTimedMessageMode {

    /** 关闭消息自动消失。 */
    OFF("off", 0),

    /** 消息保留 24 小时。 */
    HOURS_24("24h", 86_400),

    /** 消息保留 7 天。 */
    DAYS_7("7d", 604_800),

    /** 消息保留 90 天。 */
    DAYS_90("90d", 7_776_000);

    private final String wireValue;
    private final int seconds;

    GroupTimedMessageMode(String wireValue, int seconds) {
        this.wireValue = wireValue;
        this.seconds = seconds;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    public int seconds() {
        return seconds;
    }

    @JsonCreator
    public static GroupTimedMessageMode fromWireValue(String value) {
        return Arrays.stream(values())
                .filter(mode -> mode.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的限时消息模式: " + value));
    }

    /** 将协议层秒数转换为已知模式;未知秒数保持未知。 */
    public static Optional<GroupTimedMessageMode> fromSeconds(Integer seconds) {
        if (seconds == null) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(mode -> mode.seconds == seconds)
                .findFirst();
    }
}
