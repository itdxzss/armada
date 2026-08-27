package com.armada.hyperlink.template.model.vo;

/**
 * 未来任务选择模板使用的轻量候选。
 *
 * @param id 模板 ID
 * @param name 模板名称
 * @param messageType 消息类型
 * @param title 标题
 * @param version 版本
 */
public record HyperlinkTemplateOptionVO(
        Long id,
        String name,
        Integer messageType,
        String title,
        Integer version) {
}
