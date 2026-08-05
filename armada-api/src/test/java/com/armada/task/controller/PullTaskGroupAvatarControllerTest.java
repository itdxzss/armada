package com.armada.task.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.shared.security.AuthPrincipal;
import com.armada.task.model.vo.PullTaskGroupAvatarContent;
import com.armada.task.model.vo.PullTaskGroupAvatarUploadVO;
import com.armada.task.service.PullTaskGroupAvatarService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

/** 拉群任务头像 HTTP 参数衔接测试。 */
class PullTaskGroupAvatarControllerTest {

    private final PullTaskGroupAvatarService service = mock(PullTaskGroupAvatarService.class);
    private final PullTaskGroupAvatarController controller =
            new PullTaskGroupAvatarController(service);

    @Test
    void delegatesUploadPreviewAndDeleteWithTrustedTenant() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "头像.png", "image/png", new byte[] {1});
        PullTaskGroupAvatarUploadVO uploaded = new PullTaskGroupAvatarUploadVO(
                "safe.png", "头像.png",
                "/api/pull-tasks/standard/group-avatars/safe.png");
        when(service.upload(7L, file)).thenReturn(uploaded);
        when(service.content(7L, "safe.png"))
                .thenReturn(new PullTaskGroupAvatarContent("image/png", new byte[] {1, 2}));

        assertThat(controller.upload(file, principal()).data()).isEqualTo(uploaded);
        assertThat(controller.content("safe.png", principal()).getHeaders().getContentType())
                .isEqualTo(MediaType.IMAGE_PNG);
        assertThat(controller.content("safe.png", principal()).getBody())
                .containsExactly(1, 2);
        assertThat(controller.delete("safe.png", principal()).data()).isNull();

        verify(service).delete(7L, "safe.png");
    }

    private static AuthPrincipal principal() {
        return new AuthPrincipal(
                501L, 7L, "wang", "小王", "T007", "租户七",
                List.of(), List.of("tenant:pull_task:create"));
    }
}
