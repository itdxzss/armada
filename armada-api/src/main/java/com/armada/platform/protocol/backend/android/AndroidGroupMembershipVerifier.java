package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupJoinOutcome;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 使用 Android 原生群成员接口二次确认账号是否真实入群。
 */
public final class AndroidGroupMembershipVerifier {

    private static final String VERIFY_OPERATION = "group.members.verify";
    private static final String PARTICIPANTS_FIELD = "Participants";
    private static final List<String> PARTICIPANT_IDENTITY_FIELDS = List.of(
            "phone",
            "phone_number",
            "phoneNumber",
            "jid");

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;

    /**
     * 创建 Android 群成员确认器。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     */
    public AndroidGroupMembershipVerifier(
            AndroidNativeClient client,
            AndroidResponseDecoder decoder) {
        this.client = client;
        this.decoder = decoder;
    }

    /**
     * 查询目标群成员并判断当前账号已入群还是仍待管理员审批。
     *
     * @param account 当前协议账号引用
     * @param groupJid 目标群 JID
     * @param operationId 进群业务操作标识
     * @return 找到当前手机号时返回 JOINED，否则返回 PENDING_APPROVAL
     * @throws ProtocolException 查询失败或响应结构无法确认时抛出 JOIN_RESULT_UNCONFIRMED
     */
    public GroupJoinOutcome verify(
            ProtocolAccountRef account,
            String groupJid,
            String operationId) {
        try {
            AndroidDecodedResponse response = decoder.decode(
                    client.members(account.wsPhone(), groupJid));
            if (!response.success()) {
                throw unconfirmed(account, operationId, response.rawProtocolCode(), null);
            }
            JsonNode participants = response.data() == null
                    ? null
                    : response.data().path(PARTICIPANTS_FIELD);
            if (participants == null || !participants.isArray()) {
                throw unconfirmed(account, operationId, null, null);
            }
            for (JsonNode participant : participants) {
                if (matchesAccount(participant, account.wsPhone())) {
                    return GroupJoinOutcome.JOINED;
                }
            }
            return GroupJoinOutcome.PENDING_APPROVAL;
        } catch (ProtocolException ex) {
            if (ex.errorCode() == ProtocolErrorCode.JOIN_RESULT_UNCONFIRMED) {
                throw ex;
            }
            throw unconfirmed(
                    account,
                    operationId,
                    ex.protocolCode().orElse(null),
                    ex);
        }
    }

    private static boolean matchesAccount(JsonNode participant, String accountPhone) {
        for (String field : PARTICIPANT_IDENTITY_FIELDS) {
            String participantPhone = normalizePhone(participant.path(field).asText(""));
            if (accountPhone.equals(participantPhone)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizePhone(String value) {
        String normalized = value == null ? "" : value.trim();
        int at = normalized.indexOf('@');
        if (at >= 0) {
            normalized = normalized.substring(0, at);
        }
        int device = normalized.indexOf(':');
        if (device >= 0) {
            normalized = normalized.substring(0, device);
        }
        return normalized.startsWith("+") ? normalized.substring(1) : normalized;
    }

    private static ProtocolException unconfirmed(
            ProtocolAccountRef account,
            String operationId,
            String rawCode,
            Throwable cause) {
        return new ProtocolException(
                ProtocolErrorCode.JOIN_RESULT_UNCONFIRMED,
                ProtocolException.Metadata.of(0, rawCode, null, null),
                "Android 进群结果未确认 armadaAccountId=" + account.armadaAccountId(),
                cause)
                .withContext(ProtocolBackend.ANDROID, VERIFY_OPERATION, operationId);
    }
}
