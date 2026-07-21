package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ContactSaveCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.routing.ContactBackend;
import com.armada.platform.protocol.util.WhatsappJids;

import java.util.List;

/**
 * Android Zhuan 原生联系人保存 backend。
 */
public final class AndroidNativeContactAdapter implements ContactBackend {

    private static final String SAVE_OPERATION = "contact.save";

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;
    private final AndroidGroupOperationErrorMapper errorMapper;

    /**
     * 创建 Android 原生联系人保存 adapter。
     *
     * @param client Android 原生 HTTP client
     * @param decoder Android 原生响应 decoder
     * @param errorMapper Android 群操作错误 mapper
     */
    public AndroidNativeContactAdapter(
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

    /**
     * 使用当前 Android 账号保存一个联系人。
     *
     * @param command 统一联系人保存命令
     * @throws ProtocolException 参数、传输或 Android 应用层失败时抛出
     */
    @Override
    public void save(ContactSaveCommand command) {
        try {
            AndroidDecodedResponse response = decoder.decode(client.saveContacts(
                    command.account().wsPhone(),
                    List.of(normalizePhone(command.contact()))));
            if (!response.success()) {
                throw errorMapper.toException(
                        response,
                        command.account(),
                        SAVE_OPERATION,
                        command.operationId());
            }
        } catch (ProtocolException ex) {
            if (ex.backend().isPresent()) {
                throw ex;
            }
            throw ex.withContext(
                    ProtocolBackend.ANDROID,
                    SAVE_OPERATION,
                    command.operationId());
        }
    }

    private static String normalizePhone(String value) {
        String jid = WhatsappJids.userJid(value);
        String phone = jid.substring(0, jid.indexOf('@'));
        int deviceSeparator = phone.indexOf(':');
        if (deviceSeparator >= 0) {
            phone = phone.substring(0, deviceSeparator);
        }
        phone = phone.replace("+", "")
                .replace(" ", "")
                .replace("-", "")
                .replace("(", "")
                .replace(")", "");
        if (phone.isBlank() || !phone.chars().allMatch(Character::isDigit)) {
            throw new ProtocolException(
                    ProtocolErrorCode.BAD_REQUEST,
                    "Android 联系人号码格式非法");
        }
        return phone;
    }
}
