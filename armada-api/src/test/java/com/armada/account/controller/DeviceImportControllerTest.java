package com.armada.account.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.account.model.dto.DeviceImportDTO;
import com.armada.account.model.vo.AccountGroupOptionVO;
import com.armada.account.model.vo.DeviceImportVO;
import com.armada.account.service.AccountGroupService;
import com.armada.account.service.DeviceImportService;
import com.armada.admin.service.CurrentIdentityService;
import com.armada.boot.config.DeviceIngestConfig;
import com.armada.boot.config.SecurityConfig;
import com.armada.boot.security.JsonAccessDeniedHandler;
import com.armada.boot.security.JsonAuthenticationEntryPoint;
import com.armada.boot.security.SecurityJsonWriter;
import com.armada.boot.security.TokenAuthenticationFilter;
import com.armada.boot.web.DeviceImportExceptionHandler;
import com.armada.boot.web.GlobalExceptionHandler;
import com.armada.platform.auth.service.SessionService;
import com.armada.platform.tenant.mapper.TenantMapper;
import com.armada.platform.tenant.model.entity.Tenant;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.testsupport.DeviceImportTestData;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

@SpringJUnitConfig(DeviceImportControllerTest.WebConfig.class)
@WebAppConfiguration
class DeviceImportControllerTest {

    private static final String TOKEN = DeviceImportTestData.token();
    private static final String PATH = "/api/device-imports";
    @Autowired private WebApplicationContext context;
    @Autowired private DeviceImportService service;
    @Autowired private AccountGroupService groups;
    @Autowired private SessionService sessions;
    @Autowired private TenantMapper tenants;
    private MockMvc mvc;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("ARMADA_DEVICE_INGEST_CLIENTS_JSON", () -> DeviceImportTestData.clients(TOKEN, 7));
    }

    @BeforeEach
    void setUp() {
        reset(service, groups, sessions, tenants);
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        Tenant tenant = new Tenant();
        tenant.setId(7L);
        tenant.setStatus(1);
        when(tenants.selectActiveById(7)).thenReturn(tenant);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean(TokenAuthenticationFilter.class), context.getBean(FilterChainProxy.class))
                .build();
        when(service.importAccount(any(), any())).thenReturn(new DeviceImportVO(123L, "QUEUED"));
    }

    @Test
    void groupOptionsUseTokenTenantWithoutAdminIdentityOrRequestBody() throws Exception {
        when(groups.options()).thenAnswer(call -> {
            assertThat(TenantContext.get()).isEqualTo(7L);
            return List.of(new AccountGroupOptionVO(11L, "手机上传组"));
        });
        mvc.perform(get(PATH + "/groups").header("X-Ingest-Token", TOKEN)
                        .header("Authorization", "Bearer " + DeviceImportTestData.token())
                        .header("X-Tenant-Code", "other-tenant"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(11))
                .andExpect(jsonPath("$[0].name").value("手机上传组"))
                .andExpect(jsonPath("$[0].tenantId").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(sessions, service);
        assertThat(TenantContext.get()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void groupOptionsRejectMissingTokenAndDoNotExposeAdminGroupApi() throws Exception {
        mvc.perform(get(PATH + "/groups")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.code").doesNotExist());
        mvc.perform(get(PATH + "/groups").header("X-Ingest-Token", TOKEN, TOKEN))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/account-groups").header("X-Ingest-Token", TOKEN))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(groups, service, tenants);
    }

    @Test
    void groupOptionsEnforceReadOnlyRouteAndSafeErrors() throws Exception {
        String path = PATH + "/groups";
        mvc.perform(post(path).header("X-Ingest-Token", TOKEN))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().string("Allow", "GET, OPTIONS"));
        mvc.perform(options(path)).andExpect(status().isNoContent())
                .andExpect(header().string("Allow", "GET, OPTIONS"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(get(path + "?tenantId=8").header("X-Ingest-Token", TOKEN))
                .andExpect(status().isBadRequest());
        mvc.perform(get(path).header("X-Ingest-Token", TOKEN).accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isNotAcceptable());
        mvc.perform(get(path).header("X-Ingest-Token", TOKEN).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(groups);
        when(groups.options()).thenReturn(List.of());
        mvc.perform(get(path).header("X-Ingest-Token", TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException(DeviceImportTestData.token()))
                .when(groups).options();
        mvc.perform(get(path).header("X-Ingest-Token", TOKEN)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.code").doesNotExist());
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void exactSuccessContractUsesTokenTenantEvenWithBearer() throws Exception {
        doAnswer(call -> {
            assertThat(TenantContext.get()).isEqualTo(7L);
            DeviceImportDTO body = call.getArgument(0);
            assertThat(body.accountGroupId()).isEqualTo(11L);
            return new DeviceImportVO(123L, "QUEUED");
        }).when(service).importAccount(any(), any());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Ingest-Token", TOKEN).header("Authorization", "Bearer " + DeviceImportTestData.token())
                        .header("X-Tenant-Code", "other-tenant")
                        .content(DeviceImportTestData.body(11L, "999000000001", "{}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.batchId").value(123))
                .andExpect(jsonPath("$.onlinePhase").value("QUEUED"))
                .andExpect(jsonPath("$.code").doesNotExist()).andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"));
        verifyNoInteractions(sessions);
        assertThat(TenantContext.get()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void invalidAndDuplicateTokensAre401BeforeReadingPayload() throws Exception {
        for (String token : new String[]{"", DeviceImportTestData.token()}) {
            mvc.perform(post(PATH).header("X-Ingest-Token", token).content("not-json"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").isString())
                    .andExpect(jsonPath("$.code").doesNotExist());
        }
        mvc.perform(post(PATH).header("X-Ingest-Token", TOKEN, TOKEN))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service, tenants);
    }

    @Test
    void noBearerIsRequiredButTokenCannotAuthenticateAdminPaths() throws Exception {
        mvc.perform(get("/api/account-imports").header("X-Ingest-Token", TOKEN))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).header("X-Ingest-Token", TOKEN)
                        .content(DeviceImportTestData.body(11L, "999000000001", "{}")))
                .andExpect(status().isOk());
    }

    @Test
    void unavailableSelectedGroupReturnsActionable400WithoutLeakingGroupIdentity() throws Exception {
        String sentinel = DeviceImportTestData.token();
        doThrow(new BusinessException(ErrorCode.NOT_FOUND, sentinel)).when(service).importAccount(any(), any());
        var result = mvc.perform(post(PATH).header("X-Ingest-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(DeviceImportTestData.body(11L, "999000000001", "{}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("所选分组不可用，请刷新分组列表后重试"))
                .andReturn().getResponse();
        assertThat(result.getContentAsString().contains(sentinel)).isFalse();
    }

    @Test
    void malformedEnvelopeIs400WithoutEchoingBodyOrUnexpectedFields() throws Exception {
        String sentinel = DeviceImportTestData.token();
        for (String body : new String[]{"[]", "null", "{}", "{\"payload\":\"" + sentinel,
                "{\"phone\":123,\"payload\":\"{}\"}",
                "{\"phone\":\"999000000001\",\"payload\":{}}",
                "{\"phone\":\"999000000001\",\"payload\":\"{}\",\"tenantId\":8}",
                "{\"phone\":\"999000000001\",\"phone\":\"999000000002\",\"payload\":\"{}\"}",
                "{\"phone\":\"999000000001\",\"payload\":\"{}\"}",
                DeviceImportTestData.body(11L, "999000000001", "{}").replaceFirst("\\{", "{\"tenantId\":8,"),
                DeviceImportTestData.body(11L, "999000000001", "{}") + "{}"}) {
            var response = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .header("X-Ingest-Token", TOKEN).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").isString())
                    .andExpect(jsonPath("$.code").doesNotExist()).andReturn().getResponse();
            assertThat(response.getContentAsString().contains(sentinel)).isFalse();
        }
        verifyNoInteractions(service);
    }

    @Test
    void payloadLimitAppliesToStreamAndContentTypeIsEnforced() throws Exception {
        mvc.perform(post(PATH).header("X-Ingest-Token", TOKEN).contentType(MediaType.TEXT_PLAIN).content("{}"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.message").isString());
        mvc.perform(post(PATH).header("X-Ingest-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content(" ".repeat(128 * 1024 + 1)).with(request -> {
                            request.addHeader("Transfer-Encoding", "chunked");
                            return request;
                        }))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.message").isString());
        verifyNoInteractions(service);
    }

    @Test
    void methodAndOptionsContractDoesNotEnableCors() throws Exception {
        mvc.perform(get(PATH).header("X-Ingest-Token", TOKEN))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.message").isString());
        mvc.perform(options(PATH).header("Origin", "https://untrusted.invalid")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isNoContent()).andExpect(header().string("Allow", "POST, OPTIONS"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        mvc.perform(post(PATH + "?token=invalid").header("X-Ingest-Token", TOKEN))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void unsupportedAcceptMustNotFallThroughToAdminErrorEnvelope() throws Exception {
        mvc.perform(post(PATH).header("X-Ingest-Token", TOKEN).accept(MediaType.TEXT_PLAIN)
                        .contentType(MediaType.APPLICATION_JSON).content(DeviceImportTestData.body(11L, "999000000001", "{}")))
                .andExpect(status().isNotAcceptable()).andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.code").doesNotExist());
        verifyNoInteractions(service);
    }

    @Test
    void parseAndBusinessFailuresDoNotWriteSensitiveValuesToAnyApplicationLog() throws Exception {
        var root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var capture = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        capture.start();
        root.addAppender(capture);
        String sentinel = DeviceImportTestData.token();
        try {
            doThrow(new BusinessException(ErrorCode.CONFLICT, sentinel)).when(service).importAccount(any(), any());
            mvc.perform(post(PATH).header("X-Ingest-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                            .content(DeviceImportTestData.body(11L, "999000000001", sentinel)))
                    .andExpect(status().isConflict());
            mvc.perform(post(PATH).header("X-Ingest-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"payload\":\"" + sentinel))
                    .andExpect(status().isBadRequest());
            boolean leaked = capture.list.stream().anyMatch(event -> {
                String detail = event.getFormattedMessage();
                if (event.getThrowableProxy() != null) {
                    detail += ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy());
                }
                return detail.contains(sentinel) || detail.contains(TOKEN);
            });
            assertThat(leaked).isFalse();
        } finally {
            root.detachAppender(capture);
            capture.stop();
        }
    }

    @Test
    void disabledTenantAndServerFailuresUseSafeNon200ResponsesAndClearContext() throws Exception {
        when(tenants.selectActiveById(anyLong())).thenReturn(null);
        mvc.perform(post(PATH).header("X-Ingest-Token", TOKEN))
                .andExpect(status().isServiceUnavailable());
        Tenant tenant = new Tenant();
        tenant.setId(7L);
        when(tenants.selectActiveById(7)).thenReturn(tenant);
        String sentinel = DeviceImportTestData.token();
        for (RuntimeException error : new RuntimeException[]{new BusinessException(ErrorCode.CONFLICT, sentinel),
                new IllegalStateException(sentinel)}) {
            doThrow(error).when(service).importAccount(any(), any());
            var response = mvc.perform(post(PATH).header("X-Ingest-Token", TOKEN)
                            .contentType(MediaType.APPLICATION_JSON).content(DeviceImportTestData.body(11L, "999000000001", "{}")))
                    .andExpect(status().is(error instanceof BusinessException ? 409 : 500))
                    .andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.code").doesNotExist())
                    .andReturn().getResponse();
            assertThat(response.getContentAsString().contains(sentinel)).isFalse();
            assertThat(TenantContext.get()).isNull();
        }
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, DeviceIngestConfig.class, DeviceImportController.class,
            DeviceImportExceptionHandler.class, GlobalExceptionHandler.class, TokenAuthenticationFilter.class,
            JsonAccessDeniedHandler.class, JsonAuthenticationEntryPoint.class, SecurityJsonWriter.class})
    static class WebConfig {
        @Bean com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }
        @Bean DeviceImportService deviceImportService() { return mock(DeviceImportService.class); }
        @Bean AccountGroupService accountGroupService() { return mock(AccountGroupService.class); }
        @Bean SessionService sessionService() { return mock(SessionService.class); }
        @Bean CurrentIdentityService currentIdentityService() { return mock(CurrentIdentityService.class); }
        @Bean TenantMapper tenantMapper() { return mock(TenantMapper.class); }
    }
}
