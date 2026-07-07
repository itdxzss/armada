package com.armada.platform.protocol.http.group;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.result.GroupCreateParticipantResult;
import com.armada.platform.protocol.model.result.GroupCreateResult;
import com.armada.platform.protocol.port.GroupCreatePort;
import com.armada.platform.protocol.util.WhatsappJids;
import java.util.List;

/**
 * {@link GroupCreatePort} 的 HTTP adapter。
 *
 * <p>对应协议层 {@code POST /v1/groups/create};baseUrl 指向 master 时由协议层按 body.accountId
 * 路由到账号 owner worker。单账号互斥群操作由协议层 Redis group-op lock 兜底。</p>
 */
public class HttpGroupCreateAdapter implements GroupCreatePort {

    private static final String CREATE_URI = "/v1/groups/create";

    private final ProtocolHttpExecutor httpExecutor;

    public HttpGroupCreateAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    @Override
    public GroupCreateResult create(String protocolAccountId, String subject, List<String> participants, boolean announceOnly) {
        String accountId = requireText(protocolAccountId, "protocolAccountId");
        String groupSubject = requireText(subject, "subject");
        List<String> participantJids = normalizeParticipants(participants);
        CreateResponse response = httpExecutor.postTyped(
                CREATE_URI,
                new CreateRequest(accountId, groupSubject, participantJids, announceOnly),
                CreateResponse.class);
        ResultsResponse results = response.results() == null
                ? new ResultsResponse(response.groupJid(), false, List.of())
                : response.results();
        return new GroupCreateResult(
                response.groupJid(),
                results.partial(),
                results.results() == null ? List.of() : results.results().stream()
                        .map(item -> new GroupCreateParticipantResult(item.jid(), item.status(), item.rawStatus()))
                        .toList());
    }

    private static List<String> normalizeParticipants(List<String> participants) {
        if (participants == null || participants.isEmpty()) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 group create participants 参数缺失");
        }
        return participants.stream().map(WhatsappJids::userJid).toList();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 group create 参数缺失 " + fieldName);
        }
        return value.trim();
    }

    private record CreateRequest(String accountId, String subject, List<String> participants, boolean announceOnly) {
    }

    private record CreateResponse(String groupJid, ResultsResponse results) {
    }

    private record ResultsResponse(String groupJid, boolean partial, List<ParticipantResponse> results) {
    }

    private record ParticipantResponse(String jid, String status, String rawStatus) {
    }
}
