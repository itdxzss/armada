package com.armada.group.model.vo;

import com.armada.group.model.enums.GroupClassification;
import java.util.List;

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

        /** 曾被账号群同步观察到的协议位:1=Web,2=Android,3=两者。 */
        Integer syncProtocolMask,

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
        Long createdAt,

        /** 租户内 canonical 群的首次唯一分类。 */
        GroupClassification groupClassification,

        /** 是否曾属于首次上线历史群基线。 */
        Boolean isHistorical,

        /** 是否曾在上控后新发现加入。 */
        Boolean isPostControl,

        /** 群组列表运营分组 ID。 */
        Long folderId,

        /** 群组列表运营分组名称。 */
        String folderName,

        /** 当前已知邀请链接。 */
        String inviteUrl,

        /** 当前完整成员快照中属于本租户有效上控账号的管理员/群主号码。 */
        List<String> adminPhones,

        /** 是否存在可执行的在线管理员账号。 */
        Boolean availableAdmin,

        /** 可执行在线管理员账号数量。 */
        Integer availableAdminCount,

        /** 群创建者确认手机号。 */
        String creatorPhone,

        /** 群创建者国家 ISO2。 */
        String creatorCountryIso2,

        /** 群创建者国家中文名。 */
        String creatorCountryName,

        /** 群创建者国家国旗。 */
        String creatorCountryFlag,

        /** 群创建者所属大洲。 */
        String creatorContinentCode,

        /** WhatsApp 协议返回的建群时间(Unix 秒)，未知为空。 */
        Long groupCreatedAt,

        /** 群详情同步任务状态。 */
        String metadataSyncStatus,

        /** 最近一次完整 metadata 同步成功时间(epoch 毫秒)。 */
        Long metadataSyncedAt,

        /** 最近一次同步安全错误摘要。 */
        String metadataSyncError) {
}
