package com.armada.group.service;

import com.armada.group.model.dto.GroupSubjectCommandDTO;
import com.armada.group.model.dto.GroupTimedMessageCommandDTO;
import com.armada.group.model.dto.GroupSettingCommandDTO;
import com.armada.group.model.dto.GroupMemberBatchCommandDTO;
import com.armada.group.model.vo.GroupAvatarUpdateVO;
import com.armada.group.model.vo.GroupDetailVO;
import com.armada.group.model.vo.GroupLinkMemberListVO;
import com.armada.group.model.vo.GroupMemberBatchResultVO;
import org.springframework.web.multipart.MultipartFile;

/** 群详情抽屉的本地与协议实时数据聚合服务。 */
public interface GroupDetailService {

    /** 查询群详情;协议不可用时降级返回本地资料。 */
    GroupDetailVO detail(Long id);

    /** 查询实时成员列表;实时数据不可用时拒绝。 */
    GroupLinkMemberListVO members(Long id);

    /** 使用自动选号修改 WhatsApp 真实群名并同步本地镜像。 */
    void updateSubject(Long id, GroupSubjectCommandDTO dto);

    /** 使用自动选号设置 WhatsApp 群限时消息并回读确认。 */
    void updateTimedMessage(Long id, GroupTimedMessageCommandDTO dto);

    /** 使用自动选号修改一项 WhatsApp 群权限并回读确认。 */
    void updateSetting(Long id, GroupSettingCommandDTO dto);

    /** 批量提升群管理员,执行账号由后端自动选择。 */
    GroupMemberBatchResultVO promoteMembers(Long id, GroupMemberBatchCommandDTO dto);

    /** 批量取消群管理员,执行账号由后端自动选择。 */
    GroupMemberBatchResultVO demoteMembers(Long id, GroupMemberBatchCommandDTO dto);

    /** 批量踢出群成员,执行账号由后端自动选择。 */
    GroupMemberBatchResultVO kickMembers(Long id, GroupMemberBatchCommandDTO dto);

    /** 使用自动选号上传 WhatsApp 群头像并尝试同步回读 URL。 */
    GroupAvatarUpdateVO updateAvatar(Long id, MultipartFile file);
}
