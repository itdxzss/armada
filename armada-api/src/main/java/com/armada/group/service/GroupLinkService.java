package com.armada.group.service;

import com.armada.group.model.dto.GroupAnnouncementTextCommandDTO;
import com.armada.group.model.dto.GroupDescriptionCommandDTO;
import com.armada.group.model.dto.GroupLinkPreviewDTO;
import com.armada.group.model.dto.GroupLinkProfileDTO;
import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.dto.GroupPictureCommandDTO;
import com.armada.group.model.vo.GroupLinkPreviewBatchVO;
import com.armada.group.model.vo.GroupLinkVO;
import com.armada.shared.response.PageResult;
import java.util.List;
import java.util.Map;

/**
 * 群链接业务接口(分页列表、迁移分组、批量删除)。
 */
public interface GroupLinkService {

    /**
     * 群组列表主查询;labelId 可选,为空时查询当前租户全量群组列表。
     *
     * @param query 查询条件(labelId/keyword/status/sourceFileName/origin/membershipState/page/pageSize)
     * @return 分页结果
     */
    PageResult<GroupLinkVO> listByLabel(GroupLinkQuery query);

    /**
     * 批量读取 WhatsApp 真实群名称。
     *
     * <p>名称严格来自 {@code wa_group_profile.subject}；不存在当前资料或群名为空白的群链接
     * 不进入返回映射。</p>
     *
     * @param groupLinkIds 群链接 ID，可包含重复值或 null
     * @return 群链接 ID 到 WhatsApp 真实群名称的映射；无有效 ID 时返回空映射
     */
    Map<Long, String> findWhatsAppGroupNamesByIds(List<Long> groupLinkIds);

    /**
     * 更新群组列表本地资料。
     *
     * <p>只更新 Armada 本地展示字段,不调用协议层修改 WhatsApp 真实群名称或头像。</p>
     *
     * @param id  群链接 ID
     * @param dto 本地资料字段;传空字符串表示清空对应字段
     */
    void updateProfile(Long id, GroupLinkProfileDTO dto);

    /**
     * 修改 WhatsApp 真实群描述。
     *
     * @param id  群链接 ID
     * @param dto 操作账号与群描述;description 为空时清空
     */
    void updateDescription(Long id, GroupDescriptionCommandDTO dto);

    /**
     * 修改 WhatsApp 群公告文本。
     *
     * @param id  群链接 ID
     * @param dto 操作账号与公告文本
     */
    void updateAnnouncementText(Long id, GroupAnnouncementTextCommandDTO dto);

    /**
     * 修改 WhatsApp 真实群头像。
     *
     * @param id  群链接 ID
     * @param dto 操作账号与头像 URL/base64
     */
    void updatePicture(Long id, GroupPictureCommandDTO dto);

    /**
     * 批量迁移群链接到目标分组。
     *
     * <p>校验目标分组存在、linkIds 全部活跃,二者均通过后执行迁移。</p>
     *
     * @param linkIds       待迁移的群链接 ID 列表
     * @param targetLabelId 目标WS链接分组 ID
     * @return 实际迁移行数
     */
    int migrate(List<Long> linkIds, Long targetLabelId);

    /**
     * 批量实时预览群链接。
     *
     * @param dto 账号 ID 与群链接 ID 列表
     * @return 批量预览结果
     */
    GroupLinkPreviewBatchVO previewBatch(GroupLinkPreviewDTO dto);

    /**
     * 批量软删除群链接。
     *
     * @param ids 非空群链接 ID 列表，不限制数量
     * @return 实际删除行数
     */
    int batchDelete(List<Long> ids);

    /**
     * 批量设置或取消群组列表运营分组。
     *
     * @param ids 群组 ID 列表，去重后数量为 1..100
     * @param folderId 目标运营分组 ID；null 表示取消分组
     * @return 数据库实际更新行数
     */
    int assignFolder(List<Long> ids, Long folderId);
}
