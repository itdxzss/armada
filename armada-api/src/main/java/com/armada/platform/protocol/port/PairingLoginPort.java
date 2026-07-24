package com.armada.platform.protocol.port;

import com.armada.platform.protocol.model.command.PairingCodeCommand;
import com.armada.platform.protocol.model.result.PairingAccepted;
import com.armada.platform.protocol.model.result.PairingCredentialExport;

/** Web 协议手机号配对登录防腐层端口。 */
public interface PairingLoginPort {

    /**
     * 请求协议层生成随机配对码。
     *
     * @param command 手机号、会话引用和代理
     * @return 协议层受理信息；真正配对结果由 Kafka 回传
     */
    PairingAccepted requestCode(PairingCodeCommand command);

    /**
     * 导出已经完成配对的完整 Baileys 凭据。
     *
     * @param protocolAccountId 协议账号句柄
     * @return 只能进入账号凭据表的敏感 JSON
     */
    PairingCredentialExport exportCredential(String protocolAccountId);
}
