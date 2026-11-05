package com.armada.platform.protocol.port;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountIntegrity;
/** 只读档位查询端口，预览仅检查能力，绝不发出查询。 */
public interface AccountIntegrityPort {
    /** 本期是否注册了该后端的档位查询能力，不触发网络。 */
    boolean supports(ProtocolBackend backend);
    /** 用指定在线发起账号查询目标号码；异常按现有协议错误码抛出。 */
    ProtocolAccountIntegrity query(ProtocolAccountRef executor, String targetPhone);
}
