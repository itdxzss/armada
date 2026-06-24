package com.armada.marketing.model;

/**
 * 消息按钮类型(仅按钮超链模式下可配)。
 */
public enum ButtonType {

    /** 链接跳转:点击在浏览器打开 param 指定的 URL。 */
    LINK_JUMP,

    /** 复制内容:点击复制 param 指定的内容到剪贴板(如优惠码)。 */
    COPY_CONTENT,

    /** 快捷回复:点击自动回复按钮文字,无额外参数。 */
    QUICK_REPLY
}
