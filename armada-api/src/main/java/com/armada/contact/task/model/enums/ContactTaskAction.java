package com.armada.contact.task.model.enums;

import java.util.Locale;

/** 通讯录营销任务的操作动作。竞品没有删除动作，本枚举也不提供。 */
public enum ContactTaskAction {

    /** 启动。 */
    START,
    /** 暂停。 */
    PAUSE,
    /** 恢复。 */
    RESUME,
    /** 停止，终态。 */
    STOP;

    /**
     * 由接口传入的动作字符串解析枚举，大小写不敏感。
     *
     * @param value 接口原值
     * @return 对应枚举
     * @throws IllegalArgumentException 动作非法或为空时抛出
     */
    public static ContactTaskAction fromWire(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("任务动作不能为空");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (ContactTaskAction action : values()) {
            if (action.name().equals(normalized)) {
                return action;
            }
        }
        throw new IllegalArgumentException("未知的通讯录任务动作: " + value);
    }
}
