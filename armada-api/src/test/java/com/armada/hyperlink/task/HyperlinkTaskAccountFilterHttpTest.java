package com.armada.hyperlink.task;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.boot.web.GlobalExceptionHandler;
import com.armada.hyperlink.task.controller.HyperlinkTaskController;
import com.armada.hyperlink.task.controller.HyperlinkTaskJsonExceptionHandler;
import com.armada.hyperlink.task.service.HyperlinkTaskActionService;
import com.armada.hyperlink.task.service.HyperlinkTaskLifecycleService;
import com.armada.hyperlink.task.service.HyperlinkTaskListQueryService;
import com.armada.hyperlink.task.service.HyperlinkTaskQuoteService;
import com.armada.hyperlink.task.service.HyperlinkTaskQueryService;
import com.armada.shared.response.ApiResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Spring 默认容忍未知键时，accountFilter 仍必须局部 fail-closed 并返回 40001。 */
class HyperlinkTaskAccountFilterHttpTest {

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestWebConfiguration.class);
        context.refresh();
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void createRejectsUnknownAccountFilterKeyWithValidationEnvelope() throws Exception {
        mockMvc.perform(post("/api/hyperlink-tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequestWithUnknownFilterKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(
                        "accountFilter 未知字段: silentUnknown"));
        verifyNoInteractions(context.getBean(HyperlinkTaskLifecycleService.class));
    }

    @Test
    void matchCountRejectsUnknownAccountFilterKeyBeforeCallingQueryService() throws Exception {
        mockMvc.perform(post("/api/hyperlink-tasks/account-match-count")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filterSchemaVersion\":1,\"silentUnknown\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(
                        "accountFilter 未知字段: silentUnknown"));
        verifyNoInteractions(context.getBean(HyperlinkTaskQueryService.class));
    }

    @Test
    void otherControllerKeepsGlobalUnknownKeyTolerance() throws Exception {
        mockMvc.perform(post("/test/other/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"known\":\"ok\",\"silentUnknown\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value("ok"));
    }

    @Test
    void otherControllerExceptionStillUsesGlobalAdvice() throws Exception {
        mockMvc.perform(get("/test/other/fail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50000))
                .andExpect(jsonPath("$.message").value("系统繁忙,请稍后重试"));
    }

    private String validRequestWithUnknownFilterKey() {
        return """
                {
                  "version":null,
                  "sourceTaskId":null,
                  "taskName":"strict filter",
                  "messageType":3,
                  "messageContent":{
                    "linkPreviewAssetId":null,
                    "title":"Title",
                    "linkDescription":null,
                    "promotionLink":null,
                    "bodyMainAssetId":null,
                    "content":"Body",
                    "cardText":null,
                    "buttons":[{"type":"CTA_URL","displayText":"查看",
                      "url":"https://example.com","useShortLink":false}]
                  },
                  "taskMode":"instant",
                  "plannedEndAt":null,
                  "cycleIntervalMinutes":0,
                  "accountFilter":{"filterSchemaVersion":1,"silentUnknown":true},
                  "messageIntervalMinSeconds":0.5,
                  "messageIntervalMaxSeconds":0.7,
                  "maxExecutingAccounts":1,
                  "maxUseAccounts":1,
                  "maxSendPerAccount":0,
                  "startMode":"now",
                  "delayMinutes":0,
                  "dataPackageId":null,
                  "enabled":false,
                  "quoteToken":null
                }
                """;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class TestWebConfiguration implements WebMvcConfigurer {

        @Bean
        HyperlinkTaskLifecycleService lifecycleService() {
            return mock(HyperlinkTaskLifecycleService.class);
        }

        @Bean
        HyperlinkTaskQueryService queryService() {
            return mock(HyperlinkTaskQueryService.class);
        }

        @Bean
        HyperlinkTaskController hyperlinkTaskController(HyperlinkTaskLifecycleService lifecycle,
                HyperlinkTaskQueryService queryService) {
            return new HyperlinkTaskController(
                    mock(HyperlinkTaskQuoteService.class), lifecycle,
                    mock(HyperlinkTaskActionService.class),
                    queryService, mock(HyperlinkTaskListQueryService.class));
        }

        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }

        @Bean
        HyperlinkTaskJsonExceptionHandler hyperlinkTaskJsonExceptionHandler() {
            return new HyperlinkTaskJsonExceptionHandler();
        }

        @Bean
        OtherController otherController() {
            return new OtherController();
        }

        @Override
        public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            ObjectMapper tolerantMapper = new ObjectMapper()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            converters.add(new MappingJackson2HttpMessageConverter(tolerantMapper));
        }
    }

    @RestController
    @RequestMapping("/test/other")
    static class OtherController {

        @PostMapping("/echo")
        ApiResponse<String> echo(@RequestBody OtherRequest request) {
            return ApiResponse.ok(request.known());
        }

        @GetMapping("/fail")
        ApiResponse<Void> fail() {
            throw new IllegalStateException("expected test failure");
        }
    }

    record OtherRequest(String known) {
    }
}
