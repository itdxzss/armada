package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.result.GroupPictureResult;

/**
 * WhatsApp 群资料协议端口。
 *
 * <p>调用方传入协议层账号句柄和已解析的 groupJid,本端口只负责转发真实群资料修改命令。</p>
 */
public interface GroupProfilePort {

    /** 修改群名称。 */
    void updateSubject(String protocolAccountId, String groupJid, String subject);

    /** 修改群描述;description 为 null 时清空。 */
    void updateDescription(String protocolAccountId, String groupJid, String description);

    /** 修改公告文本。 */
    void updateAnnouncementText(String protocolAccountId, String groupJid, String text);

    /** 修改群头像;url/base64 二选一。 */
    GroupPictureResult updatePicture(String protocolAccountId, String groupJid, String url, String base64);

    /** 查询当前群头像 URL;无头像或协议无法回读时为 null。 */
    String getPictureUrl(String protocolAccountId, String groupJid);
}
