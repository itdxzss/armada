package com.armada.marketing.model.dto;

import com.armada.marketing.model.MessageButton;
import java.util.List;

/**
 * 营销模板创建 / 修改入参(@RequestBody)。
 */
public record MarketingTemplateDTO(

        /** 模板名(必填,租户内唯一)。 */
        String templateName,

        /** 消息类型码:1=普通超链 2=按钮超链 3=图文内容(必填)。 */
        Integer linkMode,

        /** 文本类型(搜索筛选用,dict 配置)。 */
        String textType,

        /** 图片文件 ID。 */
        Long imageFileId,

        /** 内容:标题 / 核心卖点(必填)。 */
        String content,

        /** 文本:正文 / 活动说明(选填)。 */
        String bodyText,

        /** 消息按钮:最多 3 个,仅按钮超链消息类型可配。 */
        List<MessageButton> buttons,

        /** 推广链接(二期)。 */
        String promotionLink,

        /** 备注。 */
        String remark,

        /** 是否在群消息中提醒所有成员。 */
        boolean mentionAll) {

    public MarketingTemplateDTO(
            String templateName,
            Integer linkMode,
            String textType,
            Long imageFileId,
            String content,
            String bodyText,
            List<MessageButton> buttons,
            String promotionLink,
            String remark) {
        this(templateName, linkMode, textType, imageFileId, content, bodyText,
                buttons, promotionLink, remark, false);
    }
}
