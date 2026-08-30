package com.armada.platform.protocol.model.command;

import com.armada.platform.protocol.model.enums.ProtocolBackend;

/**
 * 协议账号上线 outbox 命令请求。
 *
 * <p>这是 Slice 3 的轻量输入模型,只包含后续 Kafka 命令可排查和重新取数所需的引用字段。
 * 完整凭据、代理用户名、代理密码等敏感明文不得进入本模型。</p>
 *
 * @param accountId         Armada 账号主键
 * @param protocolAccountId 协议层账号句柄
 * @param credentialFormat  凭据格式引用
 * @param proxyId           本次上线分配的代理主键
 * @param source            命令来源,如 manual_online / batch_online
 * @param onlineAttemptId   Armada 生成的本次上线尝试 ID
 * @param previousOnlineAttemptId 代理失败重上线时关联的上一尝试 ID
 * @param protocolBackend   协议后端,默认 WEB
 * @param isBusiness        是否按 WhatsApp Business 客户端参数上线
 * @param declaredAccountType 导入申报账号类型:1个人 2商业
 * @param detectAccountType 本次 ONLINE 后是否执行一次轻量类型校验
 * @param deviceOs           主设备平台:1 Android 2 iOS
 */
public record ProtocolOnlineCommandRequest(
        Long accountId,
        String protocolAccountId,
        CredentialFormat credentialFormat,
        Long proxyId,
        String source,
        String onlineAttemptId,
        String previousOnlineAttemptId,
        ProtocolBackend protocolBackend,
        boolean isBusiness,
        Integer declaredAccountType,
        boolean detectAccountType,
        Integer deviceOs
) {

    public ProtocolOnlineCommandRequest(Long accountId,
                                        String protocolAccountId,
                                        CredentialFormat credentialFormat,
                                        Long proxyId,
                                        String source,
                                        String onlineAttemptId,
                                        String previousOnlineAttemptId,
                                        ProtocolBackend protocolBackend,
                                        boolean isBusiness,
                                        Integer declaredAccountType,
                                        boolean detectAccountType) {
        this(accountId,
                protocolAccountId,
                credentialFormat,
                proxyId,
                source,
                onlineAttemptId,
                previousOnlineAttemptId,
                protocolBackend,
                isBusiness,
                declaredAccountType,
                detectAccountType,
                1);
    }

    public ProtocolOnlineCommandRequest(Long accountId,
                                        String protocolAccountId,
                                        CredentialFormat credentialFormat,
                                        Long proxyId,
                                        String source,
                                        String onlineAttemptId,
                                        String previousOnlineAttemptId,
                                        ProtocolBackend protocolBackend) {
        this(accountId,
                protocolAccountId,
                credentialFormat,
                proxyId,
                source,
                onlineAttemptId,
                previousOnlineAttemptId,
                protocolBackend,
                false,
                1,
                false,
                1);
    }

    public ProtocolOnlineCommandRequest(Long accountId,
                                        String protocolAccountId,
                                        CredentialFormat credentialFormat,
                                        Long proxyId,
                                        String source,
                                        String onlineAttemptId,
                                        String previousOnlineAttemptId) {
        this(accountId,
                protocolAccountId,
                credentialFormat,
                proxyId,
                source,
                onlineAttemptId,
                previousOnlineAttemptId,
                ProtocolBackend.WEB,
                false,
                1,
                false,
                1);
    }

    public ProtocolOnlineCommandRequest {
        if (protocolBackend == null) {
            protocolBackend = ProtocolBackend.WEB;
        }
        if (deviceOs == null) {
            deviceOs = 1;
        }
        if (deviceOs != 1 && deviceOs != 2) {
            throw new IllegalArgumentException("deviceOs must be 1 or 2");
        }
    }
}
