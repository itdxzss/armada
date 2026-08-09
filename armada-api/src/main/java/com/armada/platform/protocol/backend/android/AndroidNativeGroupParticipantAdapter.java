package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.GroupParticipantAction;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.GroupParticipantBatchResult;
import com.armada.platform.protocol.routing.GroupParticipantBackend;
import com.armada.platform.protocol.util.WhatsappJids;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Android Zhuan 群成员变更后端。 */
public final class AndroidNativeGroupParticipantAdapter implements GroupParticipantBackend {

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;
    private final AndroidGroupOperationErrorMapper errorMapper;

    public AndroidNativeGroupParticipantAdapter(AndroidNativeClient client,
                                                AndroidResponseDecoder decoder,
                                                AndroidGroupOperationErrorMapper errorMapper) {
        this.client = client;
        this.decoder = decoder;
        this.errorMapper = errorMapper;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.ANDROID;
    }

    @Override
    public GroupParticipantBatchResult updateParticipants(
            ProtocolAccountRef account,
            String groupJid,
            List<String> participants,
            GroupParticipantAction action) {
        requireAccount(account);
        String jid = requireText(groupJid, "groupJid");
        if (participants == null || participants.isEmpty() || action == null) {
            throw new ProtocolException(ProtocolErrorCode.BAD_REQUEST, "群成员操作参数不能为空");
        }
        List<String> normalized = participants.stream().map(WhatsappJids::userJid).toList();
        return switch (action) {
            case ADD -> add(account, jid, normalized);
            case PROMOTE -> setAdmin(account, jid, normalized, true);
            case DEMOTE -> setAdmin(account, jid, normalized, false);
            case REMOVE -> remove(account, jid, normalized);
        };
    }

    private GroupParticipantBatchResult add(
            ProtocolAccountRef account,
            String groupJid,
            List<String> participants) {
        AndroidDecodedResponse response = decoder.decode(
                client.addGroupMembers(account.wsPhone(), groupJid, participants));
        if (!response.success()) {
            throw errorMapper.toException(response, account, "group.participants.add", null);
        }
        Map<String, String> errorsByPhone = memberErrors(response.data());
        List<GroupParticipantBatchResult.Item> results = new ArrayList<>();
        boolean partial = false;
        for (String participant : participants) {
            String phone = phoneOf(participant);
            if (!errorsByPhone.containsKey(phone)) {
                partial = true;
                continue;
            }
            String error = errorsByPhone.get(phone);
            String status;
            if (error == null) {
                status = "OK";
            } else if (isAlreadyIn(error)) {
                status = "ALREADY_IN";
            } else {
                status = "FAILED";
                partial = true;
            }
            results.add(new GroupParticipantBatchResult.Item(participant, status, error));
        }
        return new GroupParticipantBatchResult(partial, List.copyOf(results));
    }

    private GroupParticipantBatchResult remove(
            ProtocolAccountRef account,
            String groupJid,
            List<String> participants) {
        List<GroupParticipantBatchResult.Item> results = new ArrayList<>(participants.size());
        boolean partial = false;
        for (String participant : participants) {
            AndroidDecodedResponse response = decoder.decode(client.removeGroupMember(
                    account.wsPhone(), groupJid, participant));
            if (response.success()) {
                results.add(new GroupParticipantBatchResult.Item(
                        participant, "OK", response.rawProtocolCode()));
                continue;
            }
            ProtocolException failure = errorMapper.toException(
                    response, account, "group.participants.remove", null);
            if (failure.errorCode() == ProtocolErrorCode.TIMEOUT) {
                throw failure;
            }
            results.add(new GroupParticipantBatchResult.Item(
                    participant, "FAILED", failure.errorCode().name()));
            partial = true;
        }
        return new GroupParticipantBatchResult(partial, List.copyOf(results));
    }

    private GroupParticipantBatchResult setAdmin(
            ProtocolAccountRef account,
            String groupJid,
            List<String> participants,
            boolean enabled) {
        List<GroupParticipantBatchResult.Item> results = new ArrayList<>(participants.size());
        boolean partial = false;
        for (String participant : participants) {
            AndroidDecodedResponse response = decoder.decode(client.setGroupAdmin(
                    account.wsPhone(), groupJid, participant, enabled));
            if (response.success() || isAdminTargetState(response)) {
                results.add(new GroupParticipantBatchResult.Item(participant, "OK", response.rawProtocolCode()));
            } else {
                ProtocolException failure = errorMapper.toException(
                        response, account, enabled ? "group.admin.promote" : "group.admin.demote", null);
                results.add(new GroupParticipantBatchResult.Item(
                        participant, "FAILED", failure.errorCode().name()));
                partial = true;
            }
        }
        return new GroupParticipantBatchResult(partial, List.copyOf(results));
    }

    private static Map<String, String> memberErrors(JsonNode data) {
        Map<String, String> result = new LinkedHashMap<>();
        JsonNode members = data == null ? null : data.get("members");
        if (members == null || !members.isArray()) {
            return result;
        }
        for (JsonNode member : members) {
            String rawJid = text(member.get("jid"));
            if (rawJid == null) {
                rawJid = text(member.get("lid"));
            }
            if (rawJid == null) {
                continue;
            }
            JsonNode errorNode = member.get("err");
            String error = errorNode == null || errorNode.isNull() ? null : text(errorNode);
            result.put(phoneOf(rawJid), error);
        }
        return result;
    }

    private static boolean isAlreadyIn(String error) {
        String normalized = error.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("ALREADY_IN") || normalized.equals("409");
    }

    private static boolean isAdminTargetState(AndroidDecodedResponse response) {
        String message = response.message();
        if (message == null) {
            return false;
        }
        String normalized = message.trim().toUpperCase(Locale.ROOT);
        return normalized.equals("ALREADY_ADMIN") || normalized.equals("NOT_ADMIN");
    }

    private static String phoneOf(String jid) {
        String value = jid == null ? "" : jid.trim();
        int at = value.indexOf('@');
        return at >= 0 ? value.substring(0, at) : value;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.isTextual() ? node.asText() : node.toString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void requireAccount(ProtocolAccountRef account) {
        if (account == null) {
            throw new ProtocolException(ProtocolErrorCode.BAD_REQUEST, "群成员操作账号不能为空");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ProtocolException(ProtocolErrorCode.BAD_REQUEST, field + " 不能为空");
        }
        return value.trim();
    }
}
