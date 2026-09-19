package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.routing.GroupApprovalBackend;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/** 原生 HTTP 信封必须业务成功；批准之后由任务再次确认成员事实。 */
public final class AndroidNativeGroupApprovalAdapter implements GroupApprovalBackend {
    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;
    private final AndroidGroupOperationErrorMapper errors;
    private final AndroidGroupJoinResponseMapper invites = new AndroidGroupJoinResponseMapper();
    /** 复用 Android HTTP、信封解码和错误映射。 */
    public AndroidNativeGroupApprovalAdapter(AndroidNativeClient client, AndroidResponseDecoder decoder,
            AndroidGroupOperationErrorMapper errors) {
        this.client = client; this.decoder = decoder; this.errors = errors;
    }
    @Override
    public ProtocolBackend backend() { return ProtocolBackend.ANDROID; }
    @Override
    public String resolveGroup(ProtocolAccountRef account, String link) {
        JsonNode data = decode(client.previewGroup(account.wsPhone(), invites.inviteCode(link)), account);
        String jid = data == null ? "" : data.path("groupJid").asText("");
        if (!jid.matches("[^@\\s]+@g\\.us")) throw unknown();
        return jid;
    }
    @Override
    public List<String> pending(ProtocolAccountRef account, String groupJid) {
        JsonNode data = decode(client.pendingGroupMembers(account.wsPhone(), groupJid), account);
        if (data == null || !data.isArray()) throw unknown();
        List<String> jids = new ArrayList<>();
        for (JsonNode item : data) {
            String jid = item.path("Jid").asText("");
            if (jid.isBlank()) throw unknown();
            jids.add(jid);
        }
        return List.copyOf(jids);
    }
    @Override
    public void approve(ProtocolAccountRef account, String groupJid, String targetJid) {
        decode(client.approveGroupMember(account.wsPhone(), groupJid, targetJid), account);
    }
    private JsonNode decode(AndroidResponseEnvelope response, ProtocolAccountRef account) {
        var result = decoder.decode(response);
        if (!result.success()) throw errors.toException(result, account, "group.approval", null);
        return result.data();
    }
    private static ProtocolException unknown() {
        return new ProtocolException(ProtocolErrorCode.JOIN_RESULT_UNCONFIRMED, "Android 群审批响应不完整");
    }
}
