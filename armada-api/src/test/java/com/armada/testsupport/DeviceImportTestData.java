package com.armada.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/** 设备导入测试材料；仅在运行时生成无效凭据和临时令牌，不读取设备导出。 */
public final class DeviceImportTestData {

    private static final ObjectMapper JSON = new ObjectMapper();

    private DeviceImportTestData() {
    }

    public static String token() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String payload(String phone) {
        ObjectNode node = JSON.createObjectNode();
        for (String field : new String[]{"jid", "lid", "mcc", "mnc", "phone", "device", "country",
                "language", "platform", "pushName", "osVersion", "phoneUUID", "deviceUUID", "identityID",
                "manufacturer", "signPreKeyID", "osBuildNumber", "registrationID", "edgeRoutingInfo",
                "roProductDevice", "whatsappVersion", "identityPublicKey", "identityPrivateKey",
                "signPreKeyPublicKey", "signPreKeySignature", "signPreKeyPrivateKey",
                "clientStaticPublicKey", "clientStaticPrivateKey"}) {
            node.put(field, "test-only-invalid-" + UUID.randomUUID());
        }
        node.put("jid", phone);
        node.put("phone", phone);
        return node.toString();
    }

    public static String clients(String token, long tenantId) {
        ObjectNode row = JSON.createObjectNode();
        row.put("token", token);
        row.put("tenantId", tenantId);
        row.put("deviceOs", 2);
        row.put("accountType", 2);
        row.put("ipAllocationMode", "mixed");
        return JSON.createArrayNode().add(row).toString();
    }

    public static String body(Long groupId, String phone, String payload) {
        return JSON.createObjectNode().put("accountGroupId", groupId).put("phone", phone).put("payload", payload).toString();
    }
}
