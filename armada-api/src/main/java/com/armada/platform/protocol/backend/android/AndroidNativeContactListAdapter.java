package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;
import com.armada.platform.protocol.routing.ContactListBackend;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Android Zhuan 原生通讯录读取 backend。
 *
 * <p>读取 {@code POST /ws/v1/contacts/list/{wsPhone}}。Android 侧联系人已由 app-state
 * 同步落库，因此账号短暂离线也能拿到上一次同步结果。</p>
 */
public final class AndroidNativeContactListAdapter implements ContactListBackend {

    private static final String LIST_OPERATION = "contact.list";
    private static final String ACCOUNT_OPERATION_PREFIX = "account:";

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;
    private final AndroidGroupOperationErrorMapper errorMapper;

    /**
     * 创建 Android 原生通讯录读取 adapter。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param errorMapper Android 群操作错误 mapper
     */
    public AndroidNativeContactListAdapter(
            AndroidNativeClient client,
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
    public AccountContactSnapshot list(ProtocolAccountRef account) {
        try {
            AndroidDecodedResponse response =
                    decoder.decode(client.listContacts(account.wsPhone()));
            if (!response.success()) {
                throw errorMapper.toException(
                        response,
                        account,
                        LIST_OPERATION,
                        ACCOUNT_OPERATION_PREFIX + account.armadaAccountId());
            }
            return new AccountContactSnapshot(toContacts(response.data()), null);
        } catch (ProtocolException ex) {
            if (ex.backend().isPresent()) {
                throw ex;
            }
            throw ex.withContext(
                    ProtocolBackend.ANDROID,
                    LIST_OPERATION,
                    ACCOUNT_OPERATION_PREFIX + account.armadaAccountId());
        }
    }

    /** Go 侧 vo.SuccessJson 把列表放在 Data 字段，因此这里的 data 就是联系人数组。 */
    private static List<AccountContactSnapshot.Contact> toContacts(JsonNode data) {
        if (data == null || !data.isArray()) {
            return List.of();
        }
        List<AccountContactSnapshot.Contact> contacts = new ArrayList<>(data.size());
        for (JsonNode row : data) {
            String phone = text(row.path("phone"));
            if (phone == null) {
                continue;
            }
            contacts.add(new AccountContactSnapshot.Contact(
                    phone,
                    text(row.path("jid")),
                    text(row.path("fullName")),
                    text(row.path("firstName")),
                    text(row.path("pushName")),
                    text(row.path("businessName"))));
        }
        return List.copyOf(contacts);
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        String trimmed = node.asText().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
