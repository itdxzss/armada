package com.armada.boot.security;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.armada.account.controller.DeviceRegistrationController;
import com.armada.account.model.dto.DeviceRegistrationPermit;
import com.armada.account.service.DeviceRegistrationPermitService;
import java.math.BigDecimal;
import com.armada.platform.tenant.mapper.TenantMapper;
import com.armada.shared.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 鉴权、严格 JSON 与租户上下文清理，不依赖外部环境。 */
class DeviceRegistrationTokensTest {
    private static final String TOKEN = "test_only_registration_token_000000000000000000000";
    private static final String DEVICE = "10000000-0000-4000-8000-000000000001";
    private static final String REQUEST = "10000000-0000-4000-8000-000000000002";
    private static final String CONFIG = """
        [{"token":"%s","tenantId":7,"deviceId":"%s"}]
        """.formatted(TOKEN, DEVICE);
    private DeviceRegistrationPermitService permits() {
        var service = mock(DeviceRegistrationPermitService.class);
        when(service.current(any())).thenReturn(new DeviceRegistrationPermit(7, DEVICE, REQUEST, "187",
                new BigDecimal("0.88"), Long.MAX_VALUE, null, null));
        return service;
    }
    @Test void exactTokenDeviceAndPriceAreBoundAndEmptyConfigurationIsDisabled() {
        var tokens = new DeviceRegistrationTokens(CONFIG, "[]");
        assertThat(tokens.resolve(TOKEN, DEVICE).orElseThrow().tenantId()).isEqualTo(7L);
        assertThat(tokens.resolve(TOKEN, "other")).isEmpty(); assertThat(tokens.resolve(" " + TOKEN, DEVICE)).isEmpty();
        assertThat(new DeviceRegistrationTokens("[]", "[]").resolve(TOKEN, DEVICE)).isEmpty();
    }
    @Test void purchaseConstraintsCannotRemainInSecretConfiguration() {
        for (String field : new String[]{"requestId", "countryId", "unitPrice", "providerId", "expiresAt"}) {
            String invalid = CONFIG.replace("\"tenantId\":7", "\"tenantId\":7,\"" + field + "\":\"old\"");
            assertThatThrownBy(() -> new DeviceRegistrationTokens(invalid, "[]")).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(TOKEN);
        }
    }
    @Test void startRequiresExactlyTheRequestConfirmedOnThePhone() throws Exception {
        var filter = new DeviceRegistrationAuthenticationFilter(new DeviceRegistrationTokens(CONFIG, "[]"), mock(TenantMapper.class, RETURNS_DEEP_STUBS), permits());
        for (String body : new String[]{"{}", "{\"requestId\":\"old\",\"providerId\":\"196\"}", "{\"requestId\":\"10000000-0000-4000-8000-000000000002\",\"providerId\":\"196,62\"}"}) {
            var request = request(body); request.setRequestURI(DeviceRegistrationController.PREFIX + "start");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> fail("stale start reached controller"));
            assertThat(response.getStatus()).isIn(400, 409);
        }
        var request = request("{\"requestId\":\"10000000-0000-4000-8000-000000000002\",\"providerId\":\"196\"}");
        request.setRequestURI(DeviceRegistrationController.PREFIX + "start");
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> reached.set(true));
        assertThat(reached).isTrue(); assertThat(TenantContext.get()).isNull();
    }
    @Test void phoneBeginDoesNotRequireAPreparedControlPanelPermit() throws Exception {
        var permits = permits();
        var filter = new DeviceRegistrationAuthenticationFilter(new DeviceRegistrationTokens(CONFIG, "[]"), mock(TenantMapper.class, RETURNS_DEEP_STUBS), permits);
        var request = request("{\"requestId\":\"" + REQUEST + "\",\"countryId\":\"187\",\"unitPrice\":\"0.88\"}");
        request.setRequestURI(DeviceRegistrationController.PREFIX + "begin");
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> reached.set(true));
        assertThat(reached).isTrue();
        verifyNoInteractions(permits);
        assertThat(TenantContext.get()).isNull();
    }
    @Test void malformedDuplicatedOrUnknownFieldsNeverExposeSecret() {
        for (String invalid : new String[]{TOKEN, CONFIG + "{}", CONFIG.replace("\"tenantId\":7", "\"tenantId\":7,\"tenantId\":8"),
                CONFIG.replace("\"tenantId\":7", "\"tenantId\":7,\"extra\":1"),
                CONFIG.replace("\"tenantId\":7", "\"tenantId\":-1"), CONFIG.replace(DEVICE, "invalid")}) {
            assertThatThrownBy(() -> new DeviceRegistrationTokens(invalid, "[]")).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(TOKEN).hasNoCause();
        }
    }
    @Test void oneDeviceCannotReceiveTwoSimultaneousPermits() {
        String second = CONFIG.strip().substring(1, CONFIG.strip().length() - 1)
                .replace(TOKEN, TOKEN + "2").replace("8000-000000000002", "8000-000000000003");
        String combined = CONFIG.strip().substring(0, CONFIG.strip().length() - 1) + "," + second + "]";
        assertThatThrownBy(() -> new DeviceRegistrationTokens(combined, "[]")).isInstanceOf(IllegalStateException.class);
    }
    @Test void wrongDeviceCannotReachTenantLookup() throws Exception {
        var tenants = mock(TenantMapper.class);
        var filter = new DeviceRegistrationAuthenticationFilter(new DeviceRegistrationTokens(CONFIG, "[]"), tenants, permits());
        var request = request("{}"); request.removeHeader("X-Device-ID"); request.addHeader("X-Device-ID", "wrong");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> fail("unauthorized chain"));
        assertThat(response.getStatus()).isEqualTo(401); assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        verifyNoInteractions(tenants); assertThat(TenantContext.get()).isNull();
    }
    @Test void authenticatedRequestHasOnlyBoundTenantAndAlwaysClearsIt() throws Exception {
        var tenants = mock(TenantMapper.class, RETURNS_DEEP_STUBS);
        var filter = new DeviceRegistrationAuthenticationFilter(new DeviceRegistrationTokens(CONFIG, "[]"), tenants, permits());
        AtomicBoolean reached = new AtomicBoolean(); var request = request("{}");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            reached.set(true); assertThat(TenantContext.get()).isEqualTo(7L);
            assertThat(req.getAttribute(DeviceRegistrationController.PERMIT)).isNotNull();
        });
        assertThat(reached).isTrue(); assertThat(TenantContext.get()).isNull();
        assertThat(request.getAttribute(DeviceRegistrationController.PERMIT)).isNull();
    }
    @Test void malformedOversizedAndUnknownBodiesNeverReachController() throws Exception {
        var filter = new DeviceRegistrationAuthenticationFilter(new DeviceRegistrationTokens(CONFIG, "[]"), mock(TenantMapper.class, RETURNS_DEEP_STUBS), permits());
        for (String body : new String[]{"{}{}", "{\"tenantId\":8}", "{\"x\":1,\"x\":2}", " ".repeat(2049)}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(request(body), response, (req, res) -> fail("invalid body reached controller"));
            assertThat(response.getStatus()).isIn(400, 413);
            assertThat(response.getContentAsString()).doesNotContain(TOKEN);
        }
    }
    @Test void tokenlessTestDeviceIsExplicitAndDoesNotAcceptWrongTokens() throws Exception {
        String testConfig = "[{\"tenantId\":7,\"deviceId\":\"" + DEVICE + "\"}]";
        var tokens = new DeviceRegistrationTokens("[]", testConfig);
        assertThat(tokens.resolve(null, DEVICE).orElseThrow().tenantId()).isEqualTo(7L);
        assertThat(tokens.resolve("", DEVICE)).isEmpty();
        assertThat(tokens.resolve(TOKEN, DEVICE)).isEmpty();
        assertThat(tokens.resolve(null, "other")).isEmpty();
        assertThat(new DeviceRegistrationTokens("[]", "[]").resolve(null, DEVICE)).isEmpty();
        var filter = new DeviceRegistrationAuthenticationFilter(tokens, mock(TenantMapper.class, RETURNS_DEEP_STUBS), permits());
        var request = request("{}"); request.removeHeader("X-Registration-Token");
        AtomicBoolean reached = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            reached.set(true); assertThat(TenantContext.get()).isEqualTo(7L);
        });
        assertThat(reached).isTrue(); assertThat(TenantContext.get()).isNull();
        request.addHeader("X-Device-ID", DEVICE);
        var rejected = new MockHttpServletResponse();
        filter.doFilter(request, rejected, (req, res) -> fail("duplicate device accepted"));
        assertThat(rejected.getStatus()).isEqualTo(401);
    }
    @Test void tokenlessConfigurationRejectsAmbiguousDevicesAndUnexpectedFields() {
        String row = "{\"tenantId\":7,\"deviceId\":\"" + DEVICE + "\"}";
        for (String config : new String[]{"[" + row + "," + row + "]", "[" + row + "," + row.replace(":7", ":8") + "]",
                CONFIG, "[{\"tenantId\":0,\"deviceId\":\"" + DEVICE + "\"}]"}) {
            assertThatThrownBy(() -> new DeviceRegistrationTokens("[]", config)).isInstanceOf(IllegalStateException.class)
                    .hasMessageNotContaining(TOKEN);
        }
        assertThatThrownBy(() -> new DeviceRegistrationTokens(CONFIG, "[" + row + "]"))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void failedResultAcceptsBoundedFailureMetadata() throws Exception {
        var filter = new DeviceRegistrationAuthenticationFilter(new DeviceRegistrationTokens(CONFIG, "[]"),
                mock(TenantMapper.class, RETURNS_DEEP_STUBS), permits());
        var request = request("""
                {"requestId":"%s","phoneNumber":"12025550123","outcome":"FAILED",
                 "failureKind":"NUMBER","failureCode":"blocked","failureDetail":"目前无法登录"}
                """.formatted(REQUEST));
        request.setRequestURI(DeviceRegistrationController.PREFIX + "result");
        AtomicBoolean reached = new AtomicBoolean();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            reached.set(true);
            var result = (com.armada.account.model.dto.DeviceRegistrationResultDTO) req.getAttribute(DeviceRegistrationController.RESULT);
            assertThat(result.failureKind()).isEqualTo(com.armada.account.model.enums.DeviceRegistrationFailureKind.NUMBER);
            assertThat(result.failureCode()).isEqualTo("blocked");
            assertThat(result.failureDetail()).isEqualTo("目前无法登录");
        });
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(reached).isTrue();
        assertThat(TenantContext.get()).isNull();
    }
    @Test void failureWhitelistRemainsStrictAndOldResultsKeepThreeFieldShape() throws Exception {
        var filter = new DeviceRegistrationAuthenticationFilter(new DeviceRegistrationTokens(CONFIG, "[]"),
                mock(TenantMapper.class, RETURNS_DEEP_STUBS), permits());
        String failure = """
                {"requestId":"%s","phoneNumber":"12025550123","outcome":"FAILED",
                 "failureKind":"UNKNOWN","failureCode":"UNSPECIFIED_ERROR","failureDetail":""}
                """.formatted(REQUEST).strip();
        for (String invalid : new String[]{failure.replace("\"failureDetail\":\"\"", "\"extra\":\"\""),
                failure.replace("\"UNKNOWN\"", "\"INVALID\""), failure.replace("\"UNKNOWN\"", "\"1\""),
                failure.replace("\"failureDetail\":\"\"", "\"failureDetail\":42"),
                failure.replace("\"outcome\":\"FAILED\"", "\"outcome\":\"REGISTERED\""),
                failure.replace("\"failureDetail\":\"\"", "\"failureDetail\":\"\",\"tenantId\":8"),
                failure.replace("\"failureDetail\":\"\"", "\"failureDetail\":\"\",\"failureDetail\":\"again\""),
                failure.replace("\"failureDetail\":\"\"", "\"failureDetail\":\"" + "x".repeat(2049) + "\"")}) {
            var request = request(invalid); request.setRequestURI(DeviceRegistrationController.PREFIX + "result");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> fail("invalid failure reached controller"));
            assertThat(response.getStatus()).isIn(400, 413);
        }
        for (String outcome : new String[]{"REGISTERED", "STOPPED"}) {
            var request = request("{\"requestId\":\"" + REQUEST + "\",\"phoneNumber\":\"12025550123\",\"outcome\":\"" + outcome + "\"}");
            request.setRequestURI(DeviceRegistrationController.PREFIX + "result");
            AtomicBoolean reached = new AtomicBoolean();
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> reached.set(true));
            assertThat(reached).isTrue();
        }
    }
    private MockHttpServletRequest request(String body) {
        var request = new MockHttpServletRequest("POST", DeviceRegistrationController.PREFIX + "status");
        request.setContentType("application/json"); request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.addHeader("X-Registration-Token", TOKEN); request.addHeader("X-Device-ID", DEVICE); return request;
    }
}
