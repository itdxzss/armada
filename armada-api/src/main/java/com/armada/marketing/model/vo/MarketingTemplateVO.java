package com.armada.marketing.model.vo;

import com.armada.marketing.model.MessageButton;
import java.util.List;

/**
 * 营销模板出参(返回前端的视图对象)。
 */
public record MarketingTemplateVO(

        /** 模板 ID。 */
        Long id,

        /** 模板名。 */
        String templateName,

        /** 超链模式码:1=普通超链 2=按钮超链。 */
        Integer linkMode,

        /** 文本类型。 */
        String textType,

        /** 图片文件 ID。 */
        Long imageFileId,

        /** 内容(标题 / 核心卖点)。 */
        String content,

        /** 文本(正文)。 */
        String bodyText,

        /** 消息按钮(已由 JSON 解析为列表)。 */
        List<MessageButton> buttons,

        /** 推广链接。 */
        String promotionLink,

        /** 备注。 */
        String remark,

        /** 创建时间,epoch 毫秒(UTC 时刻);前端按 Asia/Shanghai 格式化展示。 */
        Long createdAt,

        /** 更新时间,epoch 毫秒(UTC 时刻);前端按 Asia/Shanghai 格式化展示。 */
        Long updatedAt) {
}
