package com.armada.platform.protocol.http.group;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.result.GroupJoinResult;
import com.armada.platform.protocol.port.GroupJoinPort;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@link GroupJoinPort} 的 HTTP adapter。
 *
 * <p>对应协议层 {@code POST /v1/groups/join}。完整 URL 走 {@code inviteLink},
 * 纯 code 走 {@code inviteCode};未使用的二选一字段必须不序列化,避免协议层 strict schema
 * 把 null 判成校验失败。</p>
 */
public class HttpGroupJoinAdapter implements GroupJoinPort {

    private static final String JOIN_URI = "/v1/groups/join";

    private final ProtocolHttpExecutor httpExecutor;

    public HttpGroupJoinAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    @Override
    public GroupJoinResult join(String protocolAccountId, String inviteCodeOrLink) {
        String accountId = requireText(protocolAccountId, "protocolAccountId");
        String invite = requireText(inviteCodeOrLink, "inviteCodeOrLink");
        JoinRequest request = buildRequest(accountId, invite);
        JoinResponse response = httpExecutor.postTyped(JOIN_URI, request, JoinResponse.class);
        return new GroupJoinResult(response.groupJid(), response.joined());
    }

    private static JoinRequest buildRequest(String accountId, String inviteCodeOrLink) {
        if (inviteCodeOrLink.startsWith("http://") || inviteCodeOrLink.startsWith("https://")) {
            return new JoinRequest(accountId, null, inviteCodeOrLink);
        }
        return new JoinRequest(accountId, inviteCodeOrLink, null);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 group join 参数缺失 " + fieldName);
        }
        return value;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record JoinRequest(String accountId, String inviteCode, String inviteLink) {
    }

    private record JoinResponse(String groupJid, boolean joined) {
    }
}
