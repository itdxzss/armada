package com.armada.marketing.model.vo;

import java.util.List;

/**
 * 营销域组合完成、但尚未绑定发送账号和目标的消息。
 *
 * @param messageType   TEXT/LINK/IMAGE/LINK_CARD/BUTTON_CARD
 * @param text          文本正文或图片说明
 * @param imageBytes    图片消息二进制
 * @param imageMimetype 图片消息 MIME 类型
 * @param linkCard      链接卡片
 * @param buttonCard    按钮卡片
 * @param mentionAll    是否提醒群内所有成员
 */
public record MarketingComposedMessageVO(
        String messageType,
        String text,
        byte[] imageBytes,
        String imageMimetype,
        LinkCardVO linkCard,
        ButtonCardVO buttonCard,
        boolean mentionAll) {

    /** 卡片媒体。 */
    public record MediaVO(byte[] bytes, String mimetype) {
    }

    /** 普通链接卡片。 */
    public record LinkCardVO(String url, String title, String description, MediaVO thumbnail) {
    }

    /** 按钮卡片。 */
    public record ButtonCardVO(String title, String footer, List<ButtonVO> buttons, MediaVO thumbnail) {
        public ButtonCardVO {
            buttons = buttons == null ? List.of() : List.copyOf(buttons);
        }
    }

    /** 按钮卡片中的单个按钮。 */
    public record ButtonVO(String type, String displayText, String value) {
    }
}
