package com.armada.task.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.shared.security.AuthPrincipal;
import com.armada.shared.response.PageResult;
import com.armada.task.model.dto.PullTaskStandardCreateDTO;
import com.armada.task.model.dto.PullTaskManagerSupplementDTO;
import com.armada.task.model.dto.PullTaskPullerSupplementDTO;
import com.armada.task.model.dto.PullTaskStationSupplementDTO;
import com.armada.task.model.dto.PullTaskStandardExecutionQuery;
import com.armada.task.model.enums.PullTaskAccountEntryMode;
import com.armada.task.model.dto.PullTaskStandardCreateDTOTest;
import com.armada.task.model.enums.PullTaskSelectionMode;
import com.armada.task.model.vo.PullTaskStandardCreatedVO;
import com.armada.task.model.vo.PullTaskStandardDraftVO;
import com.armada.task.model.vo.PullTaskStandardExecutionDetailVO;
import com.armada.task.model.vo.PullTaskStandardExecutionSummaryVO;
import com.armada.task.model.vo.PullTaskStandardMemberVO;
import com.armada.task.model.vo.PullTaskStandardTaskDetailVO;
import com.armada.task.model.vo.PullTaskManagerSupplementOptionsVO;
import com.armada.task.model.vo.PullTaskPullerSupplementOptionsVO;
import com.armada.task.model.vo.PullTaskStationSupplementOptionsVO;
import com.armada.task.service.PullTaskManagerSupplementService;
import com.armada.task.service.PullTaskPullerSupplementService;
import com.armada.task.service.PullTaskStationSupplementService;
import com.armada.task.service.PullTaskStandardCreateService;
import com.armada.task.service.PullTaskStandardDraftService;
import com.armada.task.service.PullTaskStandardExecutionLifecycleService;
import com.armada.task.service.PullTaskStandardReadService;
import com.armada.task.service.PullTaskStandardLifecycleService;
import com.armada.task.service.PullTaskStandardStartService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private PullTaskStandardStartService startService;
    private PullTaskStandardLifecycleService lifecycleService;
    private PullTaskStandardExecutionLifecycleService executionLifecycleService;
    private PullTaskManagerSupplementService managerSupplementService;
    private PullTaskPullerSupplementService pullerSupplementService;
    private PullTaskStationSupplementService stationSupplementService;
    private PullTaskStandardReadService readService;
    private PullTaskStandardController controller;

    @BeforeEach
    void setUp() {
        draftService = mock(PullTaskStandardDraftService.class);
        createService = mock(PullTaskStandardCreateService.class);
        startService = mock(PullTaskStandardStartService.class);
        lifecycleService = mock(PullTaskStandardLifecycleService.class);
        executionLifecycleService = mock(PullTaskStandardExecutionLifecycleService.class);
        managerSupplementService = mock(PullTaskManagerSupplementService.class);
        pullerSupplementService = mock(PullTaskPullerSupplementService.class);
        stationSupplementService = mock(PullTaskStationSupplementService.class);
        readService = mock(PullTaskStandardReadService.class);
        controller = new PullTaskStandardController(
                draftService, createService,
                new PullTaskStandardOperationServices(
                        startService, lifecycleService, executionLifecycleService,
                        new PullTaskResourceSupplementServices(
                                managerSupplementService, pullerSupplementService,
                                stationSupplementService)),
                readService);
    }

    @Test
    void planPassesEmptyListWhenNoFileUploaded() {
        when(draftService.plan(any(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(EMPTY_VIEW);

        controller.plan(null, "chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA",
                null, principal("小王", "wang"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MultipartFile>> captor = ArgumentCaptor.forClass(List.class);
        verify(draftService).plan(
                any(), anyString(), captor.capture(), anyLong(), anyString());
        // 禁止把 null 透传进 Service，空列表才是"本次没传文件"的正确表达。
        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    void planForwardsUploadedFilesInOrder() {
        when(draftService.plan(any(), any(), any(), anyLong(), anyString())).thenReturn(EMPTY_VIEW);
        MultipartFile[] files = {txt("a.txt"), txt("b.txt")};

        controller.plan(null, null, files, principal("小王", "wang"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MultipartFile>> captor = ArgumentCaptor.forClass(List.class);
        verify(draftService).plan(any(), any(), captor.capture(), anyLong(), anyString());
        assertThat(captor.getValue()).extracting(MultipartFile::getOriginalFilename)
                .containsExactly("a.txt", "b.txt");
    }

    @Test
    void planForwardsGroupFolderAndUsesNicknameAsOperatorName() {
        when(draftService.plan(any(), any(), any(), anyLong(), anyString())).thenReturn(EMPTY_VIEW);

        controller.plan(18L, null, null, principal("小王", "wang"));

        verify(draftService).plan(18L, null, List.of(), 501L, "小王");
    }

    @Test
    void planFallsBackToUsernameWhenNicknameIsBlank() {
        when(draftService.plan(any(), any(), any(), anyLong(), anyString())).thenReturn(EMPTY_VIEW);

        controller.plan(null, null, null, principal("  ", "wang"));

        verify(draftService).plan(null, null, List.of(), 501L, "wang");
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
        PullTaskStandardCreateDTO request = PullTaskStandardCreateDTOTest.request();
        PullTaskStandardCreatedVO created =
                new PullTaskStandardCreatedVO(1L, "任务", "WAIT_START", 2, 20);
        when(createService.create(request, 501L)).thenReturn(created);

        assertThat(controller.create(request, principal("小王", "wang")).data())
                .isEqualTo(created);
    }

    @Test
    void createJsonRejectsUnknownTopLevelAndNestedFields() {
        ObjectMapper objectMapper = new ObjectMapper();
        String validJson;
        try {
            validJson = objectMapper.writeValueAsString(PullTaskStandardCreateDTOTest.request());
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        assertThatThrownByJson(() -> objectMapper.readValue(
                validJson.replaceFirst("\\{", "{\"laterField\":1,"),
                PullTaskStandardCreateDTO.class));
        assertThatThrownByJson(() -> objectMapper.readValue(
                validJson.replace("\"groupName\":\"客户群\"",
                        "\"groupName\":\"客户群\",\"laterNestedField\":1"),
                PullTaskStandardCreateDTO.class));
    }

    private static void assertThatThrownByJson(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        org.assertj.core.api.Assertions.assertThatThrownBy(call)
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
    }

    @Test
    void startDelegatesTaskId() {
        assertThat(controller.start(9L).data()).isNull();

        verify(startService).start(9L);
    }

    @Test
    void lifecycleEndpointsDelegateTaskId() {
        assertThat(controller.pause(9L).data()).isNull();
        assertThat(controller.resume(9L).data()).isNull();
        assertThat(controller.end(9L).data()).isNull();

        verify(lifecycleService).pause(9L);
        verify(lifecycleService).resume(9L);
        verify(lifecycleService).end(9L);
    }

    @Test
    void executionLifecycleEndpointsDelegateOwnershipKeys() {
        assertThat(controller.pauseExecution(9L, 11L).data()).isNull();
        assertThat(controller.resumeExecution(9L, 11L).data()).isNull();
        assertThat(controller.endExecution(9L, 11L).data()).isNull();

        verify(executionLifecycleService).pause(9L, 11L);
        verify(executionLifecycleService).resume(9L, 11L);
        verify(executionLifecycleService).end(9L, 11L);
    }

    @Test
    void detailEndpointsDelegateTaskAndExecutionOwnershipKeys() {
        PullTaskStandardExecutionSummaryVO summary =
                new PullTaskStandardExecutionSummaryVO(
                        11L, 1, "chat.whatsapp.com/AAAA", "120363group@g.us",
                        2, 5, true, 1, null, null, 1_000L);
        PullTaskStandardTaskDetailVO task = new PullTaskStandardTaskDetailVO(
                9L, "任务", "EXECUTING", 1, 1,
                100L, null, 90L, null, List.of(summary));
        PullTaskStandardExecutionDetailVO execution =
                new PullTaskStandardExecutionDetailVO(summary, List.of(), List.of());
        PullTaskStandardMemberVO member = new PullTaskStandardMemberVO(
                601L, 1, "8613900000001", false,
                801L, 3, "PRIVACY", "参与者入群失败", null, 0, null);
        when(readService.task(9L)).thenReturn(task);
        when(readService.execution(9L, 11L)).thenReturn(execution);
        when(readService.members(9L, 11L)).thenReturn(List.of(member));
        PullTaskStandardExecutionQuery query = new PullTaskStandardExecutionQuery();
        PageResult<PullTaskStandardExecutionSummaryVO> page =
                PageResult.of(List.of(summary), 1, 10, 1);
        when(readService.executions(9L, query)).thenReturn(page);

        assertThat(controller.detail(9L).data()).isEqualTo(task);
        assertThat(controller.executions(9L, query).data()).isEqualTo(page);
        assertThat(controller.execution(9L, 11L).data()).isEqualTo(execution);
        assertThat(controller.members(9L, 11L).data()).containsExactly(member);
    }

    @Test
    void managerSupplementEndpointsDelegateSelectionAndImmutableCommand() {
        PullTaskManagerSupplementOptionsVO options =
                new PullTaskManagerSupplementOptionsVO(
                        0, 1, 1, 88L, false, List.of(), List.of(), List.of());
        PullTaskManagerSupplementDTO request = new PullTaskManagerSupplementDTO(
                88L, 902L, PullTaskAccountEntryMode.JOIN_BY_LINK.code(), null);
        when(managerSupplementService.options(9L, 11L, 88L)).thenReturn(options);

        assertThat(controller.managerSupplementOptions(9L, 11L, 88L).data())
                .isEqualTo(options);
        assertThat(controller.supplementManager(9L, 11L, request).data()).isNull();

        verify(managerSupplementService).options(9L, 11L, 88L);
        verify(managerSupplementService).supplement(9L, 11L, request);
    }

    @Test
    void pullerSupplementEndpointsDelegateAllFourSelectionAndEntryFields() {
        PullTaskPullerSupplementOptionsVO options =
                new PullTaskPullerSupplementOptionsVO(
                        0, 2, 2, 89L, true, List.of(), List.of());
        PullTaskPullerSupplementDTO request = new PullTaskPullerSupplementDTO(
                89L, 1, PullTaskSelectionMode.MANUAL.code(),
                PullTaskAccountEntryMode.JOIN_BY_LINK.code(), List.of(902L));
        when(pullerSupplementService.options(9L, 11L, 89L)).thenReturn(options);

        assertThat(controller.pullerSupplementOptions(9L, 11L, 89L).data())
                .isEqualTo(options);
        assertThat(controller.supplementPuller(9L, 11L, request).data()).isNull();

        verify(pullerSupplementService).options(9L, 11L, 89L);
        verify(pullerSupplementService).supplement(9L, 11L, request);
    }

    @Test
    void stationSupplementEndpointsDelegateSelectionWithoutAnEntryMode() {
        PullTaskStationSupplementOptionsVO options =
                new PullTaskStationSupplementOptionsVO(2, 1, 90L, List.of());
        PullTaskStationSupplementDTO request = new PullTaskStationSupplementDTO(
                90L, 1, PullTaskSelectionMode.MANUAL.code(), List.of(903L));
        when(stationSupplementService.options(9L, 11L, 90L)).thenReturn(options);

        assertThat(controller.stationSupplementOptions(9L, 11L, 90L).data())
                .isEqualTo(options);
        assertThat(controller.supplementStation(9L, 11L, request).data()).isNull();

        verify(stationSupplementService).options(9L, 11L, 90L);
        verify(stationSupplementService).supplement(9L, 11L, request);
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
