package com.armada.platform.protocol.backend.web;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.GroupJoinCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.routing.GroupJoinBackend;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Web/Baileys 原生进群 backend。
 *
 * <p>保持现有 {@code POST /v1/groups/join} 请求契约，并把 Web 布尔响应转换为 Armada
 * 统一进群结果。</p>
 */
public final class WebNativeGroupJoinAdapter implements GroupJoinBackend {

    private static final String JOIN_URI = "/v1/groups/join";

    private final ProtocolHttpExecutor httpExecutor;

    public WebNativeGroupJoinAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.WEB;
    }

    @Override
    public GroupJoinResult join(GroupJoinCommand command) {
        try {
            // 调用现有 Web/Baileys 进群接口，并把 JSON 响应反序列化为内部 JoinResponse。
            // request(...) 会判断输入是完整链接还是纯邀请码，只向 Web 服务发送对应的一个字段。
            JoinResponse response = httpExecutor.postTyped(
                    JOIN_URI,
                    request(command.account().protocolAccountId(), command.inviteLinkOrCode()),
                    JoinResponse.class);

            // Web 接口只返回 joined 布尔值：true 表示已经真实入群，false 表示已提交申请、等待审批。
            // 在 adapter 边界转换为统一枚举，避免上层业务继续依赖 Web 协议的原始字段语义。
            return new GroupJoinResult(
                    response.groupJid(),
                    response.joined() ? GroupJoinOutcome.JOINED : GroupJoinOutcome.PENDING_APPROVAL);
        } catch (ProtocolException ex) {
            // Web 原始异常在这里补齐统一操作上下文，供 Worker 记录标准错误码和调用标识。
            throw ex.withContext(ProtocolBackend.WEB, "group.join", command.operationId());
        }
    }

    /**
     * Web 协议要求邀请链接与邀请码二选一，未使用字段不能序列化为 null。
     */
    private static JoinRequest request(String accountId, String invite) {
        if (startsWithIgnoreCase(invite, "http://") || startsWithIgnoreCase(invite, "https://")) {
            return new JoinRequest(accountId, null, invite);
        }
        return new JoinRequest(accountId, invite, null);
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record JoinRequest(String accountId, String inviteCode, String inviteLink) {
    }

    private record JoinResponse(String groupJid, boolean joined) {
    }
}
