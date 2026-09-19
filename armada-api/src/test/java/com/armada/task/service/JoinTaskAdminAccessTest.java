package com.armada.task.service;

import com.armada.group.service.GroupLinkRegistryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.mapper.JoinTaskMapper;
import com.armada.task.mapper.JoinTaskResultMapper;
import com.armada.task.model.dto.CreateJoinTaskDTO;
import com.armada.task.model.entity.JoinTask;
import com.armada.task.model.entity.JoinTaskResult;
import com.armada.task.service.impl.JoinTaskServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 可信身份必须落库，异步阶段不能依赖 HTTP 线程上下文。 */
class JoinTaskAdminAccessTest {
    @AfterEach
    void cleanup() { TenantContext.clear(); SecurityContextHolder.clearContext(); }

    @Test
    void missingOrDifferentTenantPrincipalCannotEnableAdminAction() {
        TenantContext.set(7L);
        assertThrows(BusinessException.class, () -> JoinTaskAdminAccess.ownerForSave(null));
        login(8L, 90L);
        TenantContext.set(7L);
        assertThrows(BusinessException.class, () -> JoinTaskAdminAccess.ownerForSave(null));
    }

    @Test
    void differentOwnerCannotBeReplacedByEditing() {
        login(7L, 90L);
        assertEquals(90L, JoinTaskAdminAccess.ownerForSave(null));
        assertThrows(BusinessException.class, () -> JoinTaskAdminAccess.ownerForSave(91L));
    }

    @Test
    void createSavesTrustedOwnerAndAdminWaitingRows() throws Exception {
        login(7L, 90L);
        var tasks = mock(JoinTaskMapper.class);
        var results = mock(JoinTaskResultMapper.class);
        var saved = new AtomicReference<JoinTask>();
        doAnswer(call -> { JoinTask task = call.getArgument(0); task.setId(10L); saved.set(task); return 1; })
                .when(tasks).insert(any());
        when(tasks.selectByTenantAndId(10L)).thenAnswer(call -> saved.get());
        doAnswer(call -> {
            List<JoinTaskResult> rows = call.getArgument(0);
            assertEquals(1, rows.size()); assertEquals(1, rows.get(0).getAdminStatus()); return 1;
        }).when(results).insertResults(anyList());
        var request = new ObjectMapper().readValue("""
                {"name":"管理员测试","setAdminEnabled":true,"clearAdminsAndLeaveEnabled":true,"accountsPerLink":1,
                 "selectedAccounts":[{"accountId":30,"phone":"12345"}],
                 "linksText":"https://chat.whatsapp.com/ABCDEFGHIJKLMNOPQRSTUV"}
                """, CreateJoinTaskDTO.class);
        var service = new JoinTaskServiceImpl(tasks, results, mock(GroupLinkRegistryService.class));
        assertTrue(service.createTask(request).setAdminEnabled());
        assertTrue(saved.get().isClearAdminsAndLeaveEnabled());
        assertTrue(service.getDetail(10L).clearAdminsAndLeaveEnabled());
        assertEquals(90L, saved.get().getOwnerUserId());
    }

    @Test
    void cleanupCannotBeEnabledWithoutPromotion() throws Exception {
        login(7L, 90L);
        var tasks = mock(JoinTaskMapper.class);
        var service = new JoinTaskServiceImpl(tasks, mock(JoinTaskResultMapper.class), mock(GroupLinkRegistryService.class));
        var request = new ObjectMapper().readValue("""
                {"name":"清理测试","clearAdminsAndLeaveEnabled":true,"accountsPerLink":1,
                 "selectedAccounts":[{"accountId":30,"phone":"12345"}],
                 "linksText":"https://chat.whatsapp.com/ABCDEFGHIJKLMNOPQRSTUV"}
                """, CreateJoinTaskDTO.class);
        assertThrows(BusinessException.class, () -> service.createTask(request));
        verify(tasks, never()).insert(any());
    }

    private void login(long tenant, long user) {
        TenantContext.set(tenant);
        var principal = new AuthPrincipal(user, tenant, "test", "test", "test", "test", List.of(), List.of());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
