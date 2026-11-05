package com.armada.platform.protocol.routing;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.ProtocolAccountIntegrity;
/** 单个协议后端的只读档位查询能力。 */
public interface AccountIntegrityBackend {
    /** 返回受支持的后端。 */
    ProtocolBackend backend();
    /** 发起只读查询，返回归一化字段。 */
    ProtocolAccountIntegrity query(ProtocolAccountRef executor, String targetPhone);
}
