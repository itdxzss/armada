package com.armada.platform.protocol.http.group;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.routing.GroupApprovalBackend;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 复用 Web 的邀请预览与指定申请接口，严格区分缺失列表和空列表。 */
public final class HttpGroupApprovalAdapter implements GroupApprovalBackend {
    private static final int REQUEST_TIMEOUT_MS = 30_000;
    private final ProtocolHttpExecutor http;
    /** 使用 Web 协议专属的鉴权与超时配置。 */
    public HttpGroupApprovalAdapter(ProtocolHttpExecutor http) { this.http = http; }
    @Override
    public ProtocolBackend backend() { return ProtocolBackend.WEB; }
    @Override
    public String resolveGroup(ProtocolAccountRef account, String link) {
        JsonNode result = http.postTyped("/v1/groups/preview",
                Map.of("accountId", account.protocolAccountId(), "inviteLink", link), JsonNode.class);
        String jid = result == null ? "" : result.path("groupJid").asText("");
        if (!jid.matches("[^@\\s]+@g\\.us")) throw unknown();
        return jid;
    }
    @Override
    public List<String> pending(ProtocolAccountRef account, String groupJid) {
        JsonNode result = http.getTyped("/v1/groups/{group}/pending?accountId={account}",
                JsonNode.class, groupJid, account.protocolAccountId());
        if (result == null || !result.path("pending").isArray()) throw unknown();
        List<String> jids = new ArrayList<>();
        for (JsonNode item : result.path("pending")) {
            String jid = item.path("jid").asText("");
            if (jid.isBlank()) throw unknown();
            jids.add(jid);
        }
        return List.copyOf(jids);
    }
    @Override
    public void approve(ProtocolAccountRef account, String groupJid, String targetJid) {
        JsonNode result = http.postTyped("/v1/groups/" + groupJid + "/pending/approve",
                Map.of("accountId", account.protocolAccountId(), "participants", List.of(targetJid),
                        "timeoutMs", REQUEST_TIMEOUT_MS), JsonNode.class);
        if (result == null || result.path("partial").asBoolean(true)
                || result.path("results").size() != 1) throw unknown();
        JsonNode item = result.path("results").get(0);
        if (!targetJid.equals(item.path("jid").asText()) || !"OK".equals(item.path("status").asText())) {
            throw unknown();
        }
    }
    private static ProtocolException unknown() {
        return new ProtocolException(ProtocolErrorCode.JOIN_RESULT_UNCONFIRMED, "群审批响应缺失或结果未确认");
    }
}
