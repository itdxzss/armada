package com.armada.task.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.shared.security.AuthPrincipal;
import com.armada.task.model.dto.PullTaskStandardCreateDTO;
import com.armada.task.model.vo.PullTaskStandardCreatedVO;
import com.armada.task.model.vo.PullTaskStandardDraftVO;
import com.armada.task.service.PullTaskStandardCreateService;
import com.armada.task.service.PullTaskStandardDraftService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/** 普通群链接创建接口的参数衔接测试。 */
class PullTaskStandardControllerTest {

    private static final PullTaskStandardDraftVO EMPTY_VIEW = new PullTaskStandardDraftVO(
            1L, 1, List.of(), List.of(), List.of(), 0, 0, 0);

    private PullTaskStandardDraftService draftService;
    private PullTaskStandardCreateService createService;
    private PullTaskStandardController controller;

    @BeforeEach
    void setUp() {
        draftService = mock(PullTaskStandardDraftService.class);
        createService = mock(PullTaskStandardCreateService.class);
        controller = new PullTaskStandardController(draftService, createService);
    }

    @Test
    void planPassesEmptyListWhenNoFileUploaded() {
        when(draftService.plan(anyString(), any(), anyLong(), anyString())).thenReturn(EMPTY_VIEW);

        controller.plan("chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA", null, principal("小王", "wang"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MultipartFile>> captor = ArgumentCaptor.forClass(List.class);
        verify(draftService).plan(anyString(), captor.capture(), anyLong(), anyString());
        // 禁止把 null 透传进 Service，空列表才是"本次没传文件"的正确表达。
        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    void planForwardsUploadedFilesInOrder() {
        when(draftService.plan(any(), any(), anyLong(), anyString())).thenReturn(EMPTY_VIEW);
        MultipartFile[] files = {txt("a.txt"), txt("b.txt")};

        controller.plan(null, files, principal("小王", "wang"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MultipartFile>> captor = ArgumentCaptor.forClass(List.class);
        verify(draftService).plan(any(), captor.capture(), anyLong(), anyString());
        assertThat(captor.getValue()).extracting(MultipartFile::getOriginalFilename)
                .containsExactly("a.txt", "b.txt");
    }

    @Test
    void planUsesNicknameAsOperatorName() {
        when(draftService.plan(any(), any(), anyLong(), anyString())).thenReturn(EMPTY_VIEW);

        controller.plan(null, null, principal("小王", "wang"));

        verify(draftService).plan(null, List.of(), 501L, "小王");
    }

    @Test
    void planFallsBackToUsernameWhenNicknameIsBlank() {
        when(draftService.plan(any(), any(), anyLong(), anyString())).thenReturn(EMPTY_VIEW);

        controller.plan(null, null, principal("  ", "wang"));

        verify(draftService).plan(null, List.of(), 501L, "wang");
    }

    @Test
    void draftRemoveRowAndClearDelegateWithCurrentUserId() {
        when(draftService.current(501L)).thenReturn(EMPTY_VIEW);
        when(draftService.removeRow(9L, 501L)).thenReturn(EMPTY_VIEW);
        when(draftService.clear(501L)).thenReturn(EMPTY_VIEW);

        assertThat(controller.draft(principal("小王", "wang")).data()).isEqualTo(EMPTY_VIEW);
        assertThat(controller.removeRow(9L, principal("小王", "wang")).data())
                .isEqualTo(EMPTY_VIEW);
        assertThat(controller.clear(principal("小王", "wang")).data()).isEqualTo(EMPTY_VIEW);
    }

    @Test
    void createDelegatesRequestAndUserId() {
        PullTaskStandardCreateDTO request = new PullTaskStandardCreateDTO(
                1L, 1, "任务", null, 0, 1, 3, 8, 30, 2, 2, 1, 0, 11L, 12L, 13L);
        PullTaskStandardCreatedVO created =
                new PullTaskStandardCreatedVO(1L, "任务", "WAIT_START", 2, 20);
        when(createService.create(request, 501L)).thenReturn(created);

        assertThat(controller.create(request, principal("小王", "wang")).data())
                .isEqualTo(created);
    }

    private static MockMultipartFile txt(String fileName) {
        return new MockMultipartFile("files", fileName, "text/plain",
                "8613800138001".getBytes(StandardCharsets.UTF_8));
    }

    private static AuthPrincipal principal(String nickname, String username) {
        return new AuthPrincipal(501L, 7L, username, nickname, "T001", "租户一",
                List.of(), List.of("tenant:pull_task:create"));
    }
}
