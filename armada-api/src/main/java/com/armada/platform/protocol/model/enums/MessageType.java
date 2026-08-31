package com.armada.platform.protocol.model.enums;

/**
 * 营销消息类型。
 */
public enum MessageType {

    /** 纯文本消息。 */
    TEXT,

    /** 带普通链接的文本消息。 */
    LINK,

    /** 图片消息。 */
    IMAGE,

    /** 链接卡片消息。 */
    LINK_CARD,

    /** 按钮卡片消息。 */
    BUTTON_CARD,

    /** WhatsApp Status 动态。 */
    STATUS
}
