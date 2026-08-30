package com.armada.hyperlink.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.boot.web.GlobalExceptionHandler;
import com.armada.hyperlink.strategy.controller.HyperlinkStrategyController;
import com.armada.hyperlink.strategy.controller.HyperlinkStrategyJsonExceptionHandler;
import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyCreateDTO;
import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyQuery;
import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyUpdateDTO;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyAccountContextVO;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyDetailVO;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyListItemVO;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyOptionVO;
import com.armada.hyperlink.strategy.service.HyperlinkStrategyService;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.model.vo.HyperlinkAccountMatchCountVO;
import com.armada.shared.response.PageResult;
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
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 策略账号试算 HTTP 合同：请求体就是 raw HyperlinkAccountFilterDTO。 */
class HyperlinkStrategyAccountFilterHttpTest {

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
    void rawAccountFilterBodyBindsAndDelegatesDirectly() throws Exception {
        RecordingStrategyService service = context.getBean(RecordingStrategyService.class);

        mockMvc.perform(post("/api/hyperlink-strategies/account-match-count")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filterSchemaVersion\":1,\"countryIso2s\":[\"BR\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.availableAccountCount").value(17))
                .andExpect(jsonPath("$.data.maxConcurrentNum").value(60));

        assertThat(service.accountMatchCountCalls).isEqualTo(1);
        assertThat(service.lastFilter.filterSchemaVersion()).isEqualTo(1);
        assertThat(service.lastFilter.countryIso2s()).containsExactly("BR");
    }

    @Test
    void wrappedAccountFilterBodyFailsClosedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(post("/api/hyperlink-strategies/account-match-count")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountFilter\":{\"filterSchemaVersion\":1}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.message").value(
                        "accountFilter 未知字段: accountFilter"));

        assertThat(context.getBean(RecordingStrategyService.class).accountMatchCountCalls)
                .isZero();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class TestWebConfiguration implements WebMvcConfigurer {

        @Bean
        RecordingStrategyService strategyService() {
            return new RecordingStrategyService();
        }

        @Bean
        HyperlinkStrategyController strategyController(HyperlinkStrategyService service) {
            return new HyperlinkStrategyController(service);
        }

        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }

        @Bean
        HyperlinkStrategyJsonExceptionHandler strategyJsonExceptionHandler() {
            return new HyperlinkStrategyJsonExceptionHandler();
        }

        @Override
        public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
            ObjectMapper tolerantMapper = new ObjectMapper()
                    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            converters.add(new MappingJackson2HttpMessageConverter(tolerantMapper));
        }
    }

    static final class RecordingStrategyService implements HyperlinkStrategyService {

        private int accountMatchCountCalls;
        private HyperlinkAccountFilterDTO lastFilter;

        @Override
        public PageResult<HyperlinkStrategyListItemVO> list(HyperlinkStrategyQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public HyperlinkStrategyDetailVO detail(Long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<HyperlinkStrategyOptionVO> options(String keyword, Integer limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public HyperlinkStrategyDetailVO create(
                HyperlinkStrategyCreateDTO request, long createdBy) {
            throw new UnsupportedOperationException();
        }

        @Override
        public HyperlinkStrategyDetailVO update(
                Long id, HyperlinkStrategyUpdateDTO request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public HyperlinkStrategyAccountContextVO accountContext() {
            throw new UnsupportedOperationException();
        }

        @Override
        public HyperlinkAccountMatchCountVO accountMatchCount(HyperlinkAccountFilterDTO filter) {
            accountMatchCountCalls++;
            lastFilter = filter;
            return new HyperlinkAccountMatchCountVO(17, 4, 60);
        }
    }
}
