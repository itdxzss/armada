package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.result.GroupPreviewResult;

/**
 * 群邀请链接实时预览协议端口。
 */
public interface GroupPreviewPort {

    /**
     * 通过协议层 master 预览邀请链接。
     *
     * @param protocolAccountId 发起预览的协议账号句柄
     * @param inviteLink        完整群邀请链接
     * @return 协议层返回的群预览快照
     */
    GroupPreviewResult preview(String protocolAccountId, String inviteLink);
}
