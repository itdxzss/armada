package com.armada.task.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.shared.response.ApiResponse;
import com.armada.shared.security.AuthPrincipal;
import com.armada.task.model.dto.PullTaskGroupMarketingSettingDTO;
import com.armada.task.model.vo.PullTaskGroupMarketingSettingVO;
import com.armada.task.service.PullTaskGroupMarketingSettingService;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 拉群营销租户全局设置 Controller 委托合同测试。 */
class PullTaskGroupMarketingSettingControllerTest {

    @Test
    void getsCurrentTenantSettingThroughService() {
        PullTaskGroupMarketingSettingService service =
                mock(PullTaskGroupMarketingSettingService.class);
        PullTaskGroupMarketingSettingController controller =
                new PullTaskGroupMarketingSettingController(service);
        PullTaskGroupMarketingSettingVO setting =
                new PullTaskGroupMarketingSettingVO(false, null, null, null);
        when(service.get()).thenReturn(setting);

        ApiResponse<PullTaskGroupMarketingSettingVO> response = controller.get();

        verify(service).get();
        assertThat(response.code()).isZero();
        assertThat(response.data()).isSameAs(setting);
    }

    @Test
    void savesSettingWithAuthenticatedUserId() {
        PullTaskGroupMarketingSettingService service =
                mock(PullTaskGroupMarketingSettingService.class);
        PullTaskGroupMarketingSettingController controller =
                new PullTaskGroupMarketingSettingController(service);
        PullTaskGroupMarketingSettingDTO request =
                new PullTaskGroupMarketingSettingDTO(30, 60, 2);
        PullTaskGroupMarketingSettingVO saved =
                new PullTaskGroupMarketingSettingVO(true, 30, 60, 2);
        AuthPrincipal principal = new AuthPrincipal(
                99L, 7L, "operator", "运营员", "tenant-7", "租户七",
                List.of(), List.of("tenant:pull_task:settings"));
        when(service.save(request, 99L)).thenReturn(saved);

        ApiResponse<PullTaskGroupMarketingSettingVO> response =
                controller.save(request, principal);

        verify(service).save(request, 99L);
        assertThat(response.code()).isZero();
        assertThat(response.data()).isSameAs(saved);
    }
}
