package com.armada.marketing.model.dto;

import com.armada.marketing.model.MessageButton;
import java.util.List;

/**
 * 营销模板创建 / 修改入参(@RequestBody)。
 */
public record MarketingTemplateDTO(

        /** 模板名(必填,租户内唯一)。 */
        String templateName,

        /** 超链模式码:1=普通超链 2=按钮超链(必填)。 */
        Integer linkMode,

        /** 文本类型(搜索筛选用,dict 配置)。 */
        String textType,

        /** 图片文件 ID(≤500KB)。 */
        Long imageFileId,

        /** 内容:标题 / 核心卖点(必填)。 */
        String content,

        /** 文本:正文 / 活动说明(必填)。 */
        String bodyText,

        /** 消息按钮:最多 3 个,仅按钮超链模式可配。 */
        List<MessageButton> buttons,

        /** 推广链接(二期)。 */
        String promotionLink,

        /** 备注。 */
        String remark) {
}
