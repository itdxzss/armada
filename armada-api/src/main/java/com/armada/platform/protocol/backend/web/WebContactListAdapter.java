package com.armada.platform.protocol.backend.web;

import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.AccountContactSnapshot;
import com.armada.platform.protocol.routing.ContactListBackend;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Web/Baileys 原生通讯录读取 backend。
 *
 * <p>读取 {@code GET /v1/accounts/{protocolAccountId}/contacts}。协议层只维护 socket
 * 生命周期内的投影，账号离线时该接口会以账号不可用失败，由调用方决定是否降级。</p>
 *
 * <p>两处协议差异，都是事实不是缺陷，不要当 bug 修：
 * ① Baileys {@code Contact} 没有 firstName 概念（只有 name / notify / verifiedName），
 * 因此 Web 侧 {@code firstName} 恒为 null；
 * ② Web 的 {@code verifiedName}（商业号认证名）映射到统一模型的 {@code businessName}，
 * 与 Android 的 {@code business_name} 列同义。</p>
 */
public final class WebContactListAdapter implements ContactListBackend {

    private static final String ACCOUNT_URI_PREFIX = "/v1/accounts/";
    private static final String CONTACTS_URI_SUFFIX = "/contacts";
    private static final String LIST_OPERATION = "contact.list";
    private static final String ACCOUNT_OPERATION_PREFIX = "account:";

    private final ProtocolHttpExecutor httpExecutor;

    /**
     * 创建 Web 通讯录读取 adapter。
     *
     * @param httpExecutor Web 协议后端 HTTP 执行器
     */
    public WebContactListAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.WEB;
    }

    @Override
    public AccountContactSnapshot list(ProtocolAccountRef account) {
        try {
            ContactsResponse response = httpExecutor.getTyped(
                    ACCOUNT_URI_PREFIX + account.protocolAccountId() + CONTACTS_URI_SUFFIX,
                    ContactsResponse.class);
            if (response == null || response.contacts() == null) {
                return new AccountContactSnapshot(List.of(), null);
            }
            return new AccountContactSnapshot(
                    response.contacts().stream()
                            .map(item -> new AccountContactSnapshot.Contact(
                                    item.phone(),
                                    item.jid(),
                                    item.name(),
                                    null,
                                    item.notifyName(),
                                    item.verifiedName()))
                            .toList(),
                    response.syncedAt());
        } catch (ProtocolException ex) {
            throw ex.withContext(
                    ProtocolBackend.WEB,
                    LIST_OPERATION,
                    ACCOUNT_OPERATION_PREFIX + account.armadaAccountId());
        }
    }

    private record ContactsResponse(String accountId, Long syncedAt, List<ContactItem> contacts) {
    }

    /** notify 是 Object 的方法名，不能做 record 组件名，故改名并保留线上字段名。 */
    private record ContactItem(
            String phone,
            String jid,
            String name,
            @JsonProperty("notify") String notifyName,
            String verifiedName) {
    }
}
