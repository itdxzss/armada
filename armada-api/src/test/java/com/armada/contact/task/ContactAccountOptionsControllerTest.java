package com.armada.contact.task;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.account.model.vo.AccountGroupOptionVO;
import com.armada.account.service.AccountGroupService;
import com.armada.contact.task.controller.ContactTaskController;
import com.armada.contact.task.model.dto.ContactTaskBatchDeleteDTO;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import com.armada.contact.task.service.ContactAccountOptionsService;
import com.armada.contact.task.service.ContactTaskService;
import com.armada.promotion.channel.model.vo.PromotionChannelOptionVO;
import com.armada.promotion.channel.service.PromotionChannelService;
import com.armada.shared.tenant.TenantContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 通过真实方法权限拦截和 HTTP 序列化验证通讯录选项接口。 */
class ContactAccountOptionsControllerTest {

    private AnnotationConfigApplicationContext context;
    private ContactTaskController controller;
    private AccountGroupService groups;
    private PromotionChannelService channels;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigApplicationContext(TestConfig.class);
        controller = context.getBean(ContactTaskController.class);
        groups = context.getBean(AccountGroupService.class);
        channels = context.getBean(PromotionChannelService.class);
        TenantContext.set(7L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        context.close();
    }

    @Test
    void contactViewPermissionAloneCanReadNumericOptionsAtTheExactRoute() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "operator", null, "tenant:contact_task:view"));
        when(groups.options()).thenReturn(List.of(new AccountGroupOptionVO(17L, "通讯录组")));
        when(channels.options()).thenReturn(List.of(new PromotionChannelOptionVO(83L, "推广渠道")));

        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(get("/api/contact-tasks/account-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.groups[0].id").value(17))
                .andExpect(jsonPath("$.data.groups[0].name").value("通讯录组"))
                .andExpect(jsonPath("$.data.channels[0].id").value(83));
    }

    @Test
    void unrelatedMenuPermissionCannotReadContactOptions() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "operator", null, "tenant:account:view"));

        assertThatThrownBy(controller::accountOptions).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(groups, channels);
    }

    @Test
    void viewingOrOperatingTasksDoesNotGrantDeletePermission() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "operator", null, "tenant:contact_task:view", "tenant:contact_task:operate"));
        assertThatThrownBy(() -> controller.batchDelete(new ContactTaskBatchDeleteDTO(List.of(1L))))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(context.getBean(ContactTaskService.class));
    }

    @Test
    void deletePermissionAllowsExactBatchRouteAndReturnsCount() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(
                "operator", null, "tenant:contact_task:delete"));
        when(context.getBean(ContactTaskService.class).batchDelete(List.of(1L, 3L))).thenReturn(2);
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post("/api/contact-tasks/batch-delete")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[1,3]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(2));
    }

    @Configuration
    @EnableMethodSecurity
    static class TestConfig {
        @Bean
        ContactTaskService tasks() { return mock(ContactTaskService.class); }

        @Bean
        AccountGroupService groups() {
            return mock(AccountGroupService.class);
        }

        @Bean
        PromotionChannelService channels() {
            return mock(PromotionChannelService.class);
        }

        @Bean
        ContactTaskController controller(AccountGroupService groups, PromotionChannelService channels,
                ContactTaskService tasks) {
            return new ContactTaskController(tasks,
                    new ContactAccountOptionsService(groups, channels));
        }
    }
}
