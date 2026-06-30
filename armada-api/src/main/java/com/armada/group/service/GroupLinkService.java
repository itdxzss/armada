package com.armada.group.service;

import com.armada.group.model.dto.GroupAnnouncementTextCommandDTO;
import com.armada.group.model.dto.GroupDescriptionCommandDTO;
import com.armada.group.model.dto.GroupLinkPreviewDTO;
import com.armada.group.model.dto.GroupLinkProfileDTO;
import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.dto.GroupPictureCommandDTO;
import com.armada.group.model.dto.GroupSubjectCommandDTO;
import com.armada.group.model.vo.GroupLinkMemberListVO;
import com.armada.group.model.vo.GroupLinkPreviewBatchVO;
import com.armada.group.model.vo.GroupLinkVO;
import com.armada.shared.response.PageResult;
import java.util.List;

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
     * 更新群组列表本地资料。
     *
     * <p>只更新 Armada 本地展示字段,不调用协议层修改 WhatsApp 真实群名称或头像。</p>
     *
     * @param id  群链接 ID
     * @param dto 本地资料字段;传空字符串表示清空对应字段
     */
    void updateProfile(Long id, GroupLinkProfileDTO dto);

    /**
     * 修改 WhatsApp 真实群名称。
     *
     * @param id  群链接 ID
     * @param dto 操作账号与新群名称
     */
    void updateSubject(Long id, GroupSubjectCommandDTO dto);

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
     * 实时查询群链接对应群的成员列表。
     *
     * <p>该方法只读协议层实时快照,不持久化成员明细。</p>
     *
     * @param id 群链接 ID
     * @return 当前成员列表快照
     */
    GroupLinkMemberListVO members(Long id);

    /**
     * 批量软删除群链接。
     *
     * @param ids 群链接 ID 列表(1..100)
     * @return 实际删除行数
     */
    int batchDelete(List<Long> ids);
}
