package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.armada.hyperlink.task.controller.HyperlinkTaskController;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskListQuery;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskListExportFile;
import com.armada.hyperlink.task.service.HyperlinkTaskActionService;
import com.armada.hyperlink.task.service.HyperlinkTaskLifecycleService;
import com.armada.hyperlink.task.service.HyperlinkTaskListQueryService;
import com.armada.hyperlink.task.service.HyperlinkTaskQueryService;
import com.armada.hyperlink.task.service.HyperlinkTaskQuoteService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/** H1 Controller 只负责查询委派和可被浏览器读取的 UTF-8 CSV 文件响应。 */
class HyperlinkTaskListControllerTest {

    @Test
    void exportReturnsUtf8AttachmentCountAndExposedHeaders() {
        HyperlinkTaskListQueryService service = mock(HyperlinkTaskListQueryService.class);
        HyperlinkTaskListQuery query = new HyperlinkTaskListQuery();
        byte[] bytes = "\uFEFF\"ID\"\r\n".getBytes(StandardCharsets.UTF_8);
        org.mockito.Mockito.when(service.export(query)).thenReturn(
                new HyperlinkTaskListExportFile(
                        "hyperlink-tasks-20260829120000.csv",
                        "text/csv;charset=UTF-8", bytes, 0));
        HyperlinkTaskController controller = new HyperlinkTaskController(
                mock(HyperlinkTaskQuoteService.class),
                mock(HyperlinkTaskLifecycleService.class),
                mock(HyperlinkTaskActionService.class),
                mock(HyperlinkTaskQueryService.class), service);

        ResponseEntity<byte[]> response = controller.export(query);

        assertThat(response.getBody()).isEqualTo(bytes);
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(response.getHeaders().getFirst("X-Export-Count")).isEqualTo("0");
        assertThat(response.getHeaders().getAccessControlExposeHeaders())
                .containsExactly(HttpHeaders.CONTENT_DISPOSITION, "X-Export-Count");
        assertThat(response.getHeaders().getContentDisposition().getFilename())
                .isEqualTo("hyperlink-tasks-20260829120000.csv");
        verify(service).export(query);
    }
}
