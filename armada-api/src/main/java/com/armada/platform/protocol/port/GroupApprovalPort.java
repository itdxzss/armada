package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import java.util.List;

/** 待审核之后的群身份解析与指定申请处理，不用于进群前探测审核开关。 */
public interface GroupApprovalPort {
    /** 只读解析邀请链接对应的真实群身份。 */
    String resolveGroup(ProtocolAccountRef account, String inviteLink);
    /** 返回待审申请的原始成员 JID；响应不完整必须抛错，不能当作空列表。 */
    List<String> pending(ProtocolAccountRef account, String groupJid);
    /** 仅处理指定账号的申请；调用后仍由业务查询成员事实确认真实入群。 */
    void approve(ProtocolAccountRef account, String groupJid, String targetJid);
}
