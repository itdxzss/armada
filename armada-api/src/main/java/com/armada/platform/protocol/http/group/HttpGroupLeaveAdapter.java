package com.armada.platform.protocol.http.group;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.routing.GroupLeaveBackend;

/** Web/Baileys 退群 HTTP 后端。 */
public final class HttpGroupLeaveAdapter implements GroupLeaveBackend {

    private final ProtocolHttpExecutor httpExecutor;

    public HttpGroupLeaveAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.WEB;
    }

    @Override
    public void leave(ProtocolAccountRef account, String groupJid) {
        if (account == null || groupJid == null || groupJid.isBlank()) {
            throw new ProtocolException(ProtocolErrorCode.BAD_REQUEST, "退群参数不能为空");
        }
        try {
            httpExecutor.postVoid(
                    "/v1/groups/%s/leave".formatted(groupJid.trim()),
                    new LeaveRequest(account.protocolAccountId()));
        } catch (ProtocolException ex) {
            String code = ex.protocolCode().orElse("");
            if (!"ALREADY_LEFT".equalsIgnoreCase(code)
                    && !"ACCOUNT_NOT_PARTICIPANT".equalsIgnoreCase(code)) {
                throw ex.withContext(ProtocolBackend.WEB, "group.leave", null);
            }
        }
    }

    private record LeaveRequest(String accountId) {
    }
}
