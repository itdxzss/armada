package com.armada.boot.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.armada.boot.config.DeviceRegistrationConfig;
import com.armada.shared.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 云手机目录只暴露本租户配置的非秘密字段，保留既有设备认证语义。 */
class CloudRegistrationDevicesTest {
    private static String row(int tenant, int device, String phone) {
        return "{\"token\":\"test_cloud_token_000000000000000000000000000000" + device
                + "\",\"tenantId\":" + tenant + ",\"deviceId\":\"10000000-0000-4000-8000-00000000000"
                + device + "\",\"cloudPhoneId\":\"" + phone + "\",\"displayName\":\"CP-" + device + "\"}";
    }

    @Test void isolatesDirectoryAndNeverSerializesCredentials() throws Exception {
        var tokens = new DeviceRegistrationTokens("[" + row(1, 1, "123") + "," + row(2, 2, "456") + "]", "[]");
        var visible = tokens.cloudDevices(1L);
        assertEquals(1, visible.size());
        assertEquals("123", visible.get(0).cloudPhoneId());
        assertEquals("CP-1", visible.get(0).displayName());
        var json = new ObjectMapper().writeValueAsString(visible);
        assertFalse(json.contains("token"));
        assertFalse(json.contains("digest"));
        assertFalse(json.contains("456"));
        assertTrue(tokens.cloudDevices(null).isEmpty());
        assertTrue(tokens.cloudDevices(3L).isEmpty());
        assertTrue(tokens.resolve("test_cloud_token_0000000000000000000000000000001",
                "10000000-0000-4000-8000-000000000001").isPresent());
    }

    @Test void rejectsDuplicatePhysicalDeviceAndIncompleteMetadata() {
        assertThrows(IllegalStateException.class, () -> new DeviceRegistrationTokens(
                "[" + row(1, 1, "123") + "," + row(2, 2, "123") + "]", "[]"));
        assertThrows(IllegalStateException.class, () -> new DeviceRegistrationTokens(
                "[" + row(1, 1, "123").replace(",\"displayName\":\"CP-1\"", "") + "]", "[]"));
        assertThrows(IllegalStateException.class, () -> new DeviceRegistrationTokens(
                "[" + row(1, 1, "../123") + "]", "[]"));
    }

    @Test void ordinaryAndTokenlessDevicesAreNotCloudInventory() {
        String legacy = row(1, 1, "123").replace(",\"cloudPhoneId\":\"123\",\"displayName\":\"CP-1\"", "");
        var tokens = new DeviceRegistrationTokens("[" + legacy + "]",
                "[{\"tenantId\":1,\"deviceId\":\"10000000-0000-4000-8000-000000000002\"}]");
        assertTrue(tokens.cloudDevices(1L).isEmpty());
        assertTrue(tokens.resolve(null, "10000000-0000-4000-8000-000000000002").isPresent());
    }

    @Test void directoryServiceUsesTheCallingTenantsContextEachTime() {
        var tokens = new DeviceRegistrationTokens("[" + row(1, 1, "123") + "," + row(2, 2, "456") + "]", "[]");
        var service = new DeviceRegistrationConfig().cloudRegistrationDeviceService(tokens);
        try {
            TenantContext.set(1L);
            assertEquals("123", service.currentDevices().get(0).cloudPhoneId());
            TenantContext.set(2L);
            assertEquals("456", service.currentDevices().get(0).cloudPhoneId());
            TenantContext.clear();
            assertTrue(service.currentDevices().isEmpty());
        } finally { TenantContext.clear(); }
    }
}
