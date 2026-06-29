package com.armada.platform.protocol.http.group;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.result.GroupPreviewResult;
import com.armada.platform.protocol.port.GroupPreviewPort;

import java.time.Instant;

/**
 * {@link GroupPreviewPort} 的 HTTP adapter。
 *
 * <p>baseUrl 配到协议层 master;master 根据请求体 accountId 路由到 owner worker。</p>
 */
public class HttpGroupPreviewAdapter implements GroupPreviewPort {

    private static final String PREVIEW_URI = "/v1/groups/preview";

    private final ProtocolHttpExecutor httpExecutor;

    public HttpGroupPreviewAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    @Override
    public GroupPreviewResult preview(String protocolAccountId, String inviteLink) {
        String accountId = requireText(protocolAccountId, "protocolAccountId");
        String link = requireText(inviteLink, "inviteLink");
        PreviewResponse response = httpExecutor.postTyped(
                PREVIEW_URI, new PreviewRequest(accountId, link), PreviewResponse.class);
        return new GroupPreviewResult(
                response.groupJid(),
                response.subject(),
                response.memberCount() == null ? response.size() : response.memberCount(),
                response.isBanned(),
                response.ownerJid(),
                response.desc(),
                response.announce(),
                response.restrict(),
                response.inviteCode(),
                response.previewAt());
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 group preview 参数缺失 " + fieldName);
        }
        return value;
    }

    private record PreviewRequest(String accountId, String inviteLink) {
    }

    private record PreviewResponse(
            String groupJid,
            String subject,
            Integer memberCount,
            Integer size,
            boolean isBanned,
            String ownerJid,
            String desc,
            Boolean announce,
            Boolean restrict,
            String inviteCode,
            Instant previewAt) {
    }
}
