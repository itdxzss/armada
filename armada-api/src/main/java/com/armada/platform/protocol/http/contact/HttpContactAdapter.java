package com.armada.platform.protocol.http.contact;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ContactSaveCommand;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.routing.ContactBackend;
import com.armada.platform.protocol.util.WhatsappJids;
import org.springframework.util.StringUtils;

/**
 * Web/Baileys 联系人保存能力的 HTTP adapter。
 *
 * <p>对应协议层 {@code POST /v1/contacts/{jid}/save}。该接口只表示联系人保存动作执行完成,
 * 不表达对方是否确认好友关系。</p>
 */
public class HttpContactAdapter implements ContactBackend {

    private static final String SAVE_URI_TEMPLATE = "/v1/contacts/%s/save";

    private final ProtocolHttpExecutor httpExecutor;

    /**
     * 创建联系人保存 HTTP adapter。
     *
     * @param httpExecutor 协议层 HTTP 执行器
     */
    public HttpContactAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.WEB;
    }

    @Override
    public void save(ContactSaveCommand command) {
        String accountId = requireText(
                command.account().protocolAccountId(), "protocolAccountId");
        String jid = WhatsappJids.userJid(command.contact());
        String displayName = displayName(command.name(), jid);
        httpExecutor.postVoid(
                SAVE_URI_TEMPLATE.formatted(jid),
                new SaveContactRequest(accountId, new ContactBody(displayName)));
    }

    private static String displayName(String name, String jid) {
        if (StringUtils.hasText(name)) {
            return name.trim();
        }
        int at = jid.indexOf('@');
        return at > 0 ? jid.substring(0, at) : jid;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 contact 参数缺失 " + fieldName);
        }
        return value.trim();
    }

    private record SaveContactRequest(String accountId, ContactBody contact) {
    }

    private record ContactBody(String name) {
    }
}
