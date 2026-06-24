package com.armada.marketing.model;

/**
 * 消息按钮值对象。营销模板按钮超链模式下使用,最多 3 个。
 */
public record MessageButton(

        /** 按钮类型:链接跳转 / 复制内容 / 快捷回复。 */
        ButtonType type,

        /** 按钮文字(展示在按钮上的文案)。 */
        String text,

        /** 参数:LINK_JUMP=跳转 URL、COPY_CONTENT=复制内容;QUICK_REPLY 无参数(null)。 */
        String param) {
}
