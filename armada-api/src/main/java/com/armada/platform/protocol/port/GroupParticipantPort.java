package com.armada.platform.protocol.port;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import java.util.List;

/**
 * WhatsApp 群成员变更协议端口。
 */
public interface GroupParticipantPort {

    /**
     * 使用同一个在线协议账号批量添加成员、变更成员角色或移除成员。
     *
     * <p>本端口接受 {@link GroupParticipantAction} 定义的添加、升管理员、降管理员和移除动作。
     * 一次请求中的全部目标 JID 必须由同一账号在同一群内执行，调用方负责事先完成执行账号选择、
     * 群主保护等业务校验。</p>
     *
     * <p>协议层按成员逐项返回结果，部分成功不会被折叠成整体异常；调用方应依据返回对象的
     * {@code partial} 和逐 JID 状态向前端展示成功项与失败原因。</p>
     *
     * @param protocolAccountId 协议层账号句柄
     * @param groupJid          WhatsApp 群 JID
     * @param participants      去重后的目标成员 JID，不能为空
     * @param action            添加、升管理员、降管理员或移除动作
     * @return 协议层批量状态和逐成员回执
     * @throws ProtocolException 当参数缺失、执行账号无权限、协议超时或协议调用失败时抛出
     */
    GroupParticipantBatchResult updateParticipants(
            String protocolAccountId,
            String groupJid,
            List<String> participants,
            GroupParticipantAction action);
}
