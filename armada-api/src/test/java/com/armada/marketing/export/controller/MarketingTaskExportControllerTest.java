package com.armada.marketing.export.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.marketing.export.model.dto.MarketingTaskExportRequestDTO;
import com.armada.marketing.export.model.vo.MarketingTaskExportFile;
import com.armada.marketing.export.model.vo.MarketingTaskExportJobVO;
import com.armada.marketing.export.service.MarketingTaskExportService;
import com.armada.shared.security.AuthPrincipal;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@ExtendWith(MockitoExtension.class)
class MarketingTaskExportControllerTest {

    @Mock
    private MarketingTaskExportService service;

    @TempDir
    private Path tempDir;

    @Test
    void exposesOnlyDedicatedPermissionProtectedRoutes() throws Exception {
        RequestMapping root = MarketingTaskExportController.class.getAnnotation(RequestMapping.class);
        PreAuthorize permission = MarketingTaskExportController.class.getAnnotation(PreAuthorize.class);
        Method create = MarketingTaskExportController.class.getMethod(
                "create", MarketingTaskExportRequestDTO.class, AuthPrincipal.class);
        Method status = MarketingTaskExportController.class.getMethod(
                "status", Long.class, AuthPrincipal.class);
        Method download = MarketingTaskExportController.class.getMethod(
                "download", Long.class, AuthPrincipal.class);

        assertThat(root.value()).containsExactly("/api/marketing-task-exports");
        assertThat(permission.value()).isEqualTo("hasAuthority('tenant:marketing_task:export')");
        assertThat(create.getAnnotation(PostMapping.class).value()).isEmpty();
        assertThat(status.getAnnotation(GetMapping.class).value()).containsExactly("/{id}");
        assertThat(download.getAnnotation(GetMapping.class).value()).containsExactly("/{id}/download");
    }

    @Test
    void createDelegatesUntrustedRequestAndAuthenticatedPrincipal() {
        MarketingTaskExportController controller = new MarketingTaskExportController(service);
        MarketingTaskExportRequestDTO request = new MarketingTaskExportRequestDTO(
                "FULL", List.of(7L), List.of());
        AuthPrincipal principal = new AuthPrincipal(
                5L, 3L, "tester", "测试", "t3", "租户3", List.of(), List.of());
        MarketingTaskExportJobVO job = new MarketingTaskExportJobVO(
                88L, "FULL", "PENDING", 1000L, null,
                0, 0, null, 1000L, null, false);
        when(service.createJob(request, principal)).thenReturn(job);

        var response = controller.create(request, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).isEqualTo(job);
        verify(service).createJob(request, principal);
    }

    @Test
    void downloadUsesServerOwnedPathAndUtf8AttachmentHeader() throws Exception {
        Path file = tempDir.resolve("export.xlsx");
        Files.write(file, new byte[] {1, 2, 3});
        AuthPrincipal principal = new AuthPrincipal(
                5L, 3L, "tester", "测试", "t3", "租户3", List.of(), List.of());
        when(service.getDownload(88L, principal)).thenReturn(new MarketingTaskExportFile(
                file,
                "营销任务.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                3L));
        MarketingTaskExportController controller = new MarketingTaskExportController(service);

        var response = controller.download(88L, principal);

        assertThat(response.getHeaders().getContentLength()).isEqualTo(3L);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("UTF-8");
        assertThat(response.getHeaders().getAccessControlExposeHeaders())
                .containsExactly(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(response.getBody()).isNotNull();
        verify(service).getDownload(88L, principal);
    }
}
