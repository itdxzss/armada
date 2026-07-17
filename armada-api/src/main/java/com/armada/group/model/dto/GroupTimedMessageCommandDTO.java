package com.armada.group.model.dto;

import com.armada.group.model.enums.GroupTimedMessageMode;

/**
 * 群限时消息设置请求。
 *
 * @param mode WhatsApp 支持的限时消息档位
 */
public record GroupTimedMessageCommandDTO(GroupTimedMessageMode mode) {
}
