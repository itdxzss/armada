package com.armada.group.model.vo;

/**
 * 群链接出参(返回前端的视图对象)。
 * 时间字段为 epoch 毫秒(UTC 时刻);前端按 Asia/Shanghai 格式化展示。
 */
public record GroupLinkVO(
        /** 群链接 ID。 */
        Long id,

        /** 归一化链接 URL。 */
        String url,

        /** 列表展示群名称:运营自定义群名优先,为空时回退 WhatsApp 真实群名。 */
        String groupName,

        /** WhatsApp 真实群名称,来自协议预览。 */
        String waSubject,

        /** WhatsApp 群 JID,协议层操作群的真实标识。 */
        String groupJid,

        /** 来源文件名(可为 null)。 */
        String sourceFileName,

        /** 群状态码:UNCHECKED/AVAILABLE/BANNED/LINK_INVALID/UNAVAILABLE。 */
        String status,

        /** 群状态中文标签。 */
        String statusLabel,

        /** 原始健康状态:1=可用 2=链接失效 3=不可用;null=未检测。 */
        Integer healthStatus,

        /** 是否被 WhatsApp 封禁;null=未知。 */
        Boolean banned,

        /** 群人数:健康 currentCount 优先,为空时回退 preview memberSize。 */
        Integer memberCount,

        /** 我方已提权管理员账号,多个用逗号分隔;为空时前端展示待分配。 */
        String admin,

        /** 首次进入群组池来源码。 */
        Integer origin,

        /** 来源展示文案:导入链接/进群任务/拉群任务/自建群。 */
        String source,

        /** 我方与群关系码。 */
        Integer membershipState,

        /** 我方与群关系展示文案。 */
        String membershipStateLabel,

        /** 运营备注。 */
        String remark,

        /** 群头像 URL。 */
        String avatarUrl,

        /** 群主号码,来自协议预览。 */
        String ownerPhone,

        /** 最近一次预览/解析成功时间,epoch 毫秒。 */
        Long lastPreviewAt,

        /** 最近一次健康检测时间,epoch 毫秒。 */
        Long lastCheckAt,

        /** 最近一次健康检测失败原因。 */
        String lastHealthError,

        /** 创建时间,epoch 毫秒(UTC)。 */
        Long createdAt) {
}
