package com.armada.hyperlink.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.hyperlink.task.controller.HyperlinkTaskDetailController;
import com.armada.hyperlink.task.controller.HyperlinkTaskExportController;
import com.armada.hyperlink.task.model.vo.HyperlinkRecipientItemVO;
import com.armada.hyperlink.task.model.vo.HyperlinkRecipientRow;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskExportJobVO;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskSummaryVO;
import com.armada.hyperlink.task.service.HyperlinkRecipientCsvWriter;
import java.io.StringWriter;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/** H4 HTTP/VO 与竞品八列 CSV 的逐字段冻结测试。 */
class HyperlinkTaskH4ContractTest {

    @Test
    void freezesSummaryRecipientAndExportJobFields() {
        assertThat(componentNames(HyperlinkTaskSummaryVO.class)).containsExactly(
                "id", "taskName", "recipientTotal", "sendTotal", "successNum",
                "deliveredNum", "readNum", "failedNum", "unregisteredNum",
                "usedAccountCount", "invalidAccountCount", "clickUvNum", "clickTotal",
                "actualConcurrency", "executionDurationSec", "metricsUpdatedAt",
                "firstVisitAt", "lastVisitAt");
        assertThat(componentNames(HyperlinkRecipientItemVO.class)).containsExactly(
                "id", "recipientPhone", "recipientCountryIso2", "accountId", "senderPhone",
                "senderCountryIso2", "status", "failCode", "failReason", "statusAt");
        assertThat(componentNames(HyperlinkTaskExportJobVO.class)).containsExactly(
                "id", "exportType", "status", "snapshotAt", "fileName", "rowCount",
                "errorMessage", "createdAt", "finishedAt", "downloadReady");
    }

    @Test
    void freezesAllH4PathsAndServerSidePermissions() throws ReflectiveOperationException {
        var summary = HyperlinkTaskDetailController.class
                .getMethod("summary", long.class);
        assertThat(summary.getAnnotation(GetMapping.class).value())
                .containsExactly("/{id}/summary");
        assertThat(summary.getAnnotation(PreAuthorize.class).value())
                .contains("tenant:hyperlink_task:view");

        var recipients = Arrays.stream(HyperlinkTaskDetailController.class.getMethods())
                .filter(method -> method.getName().equals("recipients"))
                .findFirst().orElseThrow();
        assertThat(recipients.getAnnotation(GetMapping.class).value())
                .containsExactly("/{id}/recipients");
        assertThat(recipients.getAnnotation(PreAuthorize.class).value())
                .contains("tenant:hyperlink_task:view");

        var export = Arrays.stream(HyperlinkTaskDetailController.class.getMethods())
                .filter(method -> method.getName().equals("exportRecipients"))
                .findFirst().orElseThrow();
        assertThat(export.getAnnotation(PostMapping.class).value())
                .containsExactly("/{id}/recipients/export");
        assertThat(export.getAnnotation(PreAuthorize.class).value())
                .contains("tenant:hyperlink_task:export");

        assertThat(HyperlinkTaskExportController.class.getAnnotation(PreAuthorize.class).value())
                .contains("tenant:hyperlink_task:export");
        assertThat(HyperlinkTaskExportController.class.getMethod(
                "status", long.class, com.armada.shared.security.AuthPrincipal.class)
                .getAnnotation(GetMapping.class).value()).containsExactly("/{id}");
        assertThat(Arrays.stream(HyperlinkTaskExportController.class.getMethods())
                .filter(method -> method.getName().equals("download"))
                .findFirst().orElseThrow().getAnnotation(GetMapping.class).value())
                .containsExactly("/{id}/download");
    }

    @Test
    void writesBomAndEveryCompetitorCsvColumnInFixedOrder() throws Exception {
        HyperlinkRecipientRow row = new HyperlinkRecipientRow();
        row.setId(1L);
        row.setRecipientPhone("+628123456789");
        row.setRecipientCountryIso2("ID");
        row.setAccountId(9L);
        row.setSenderPhone("+12025550123");
        row.setSenderCountryIso2("US");
        row.setStatusCode(7);
        row.setFailCode("NOT_REGISTERED");
        row.setFailReason("号码未注册");
        row.setStatusAt(0L);

        StringWriter output = new StringWriter();
        HyperlinkRecipientCsvWriter writer = new HyperlinkRecipientCsvWriter();
        writer.writeHeader(output);
        writer.writeRow(output, row);

        String[] lines = output.toString().split("\\r\\n");
        assertThat(lines[0]).isEqualTo(
                "\ufeff收信号码,收信国家,发送账号,发信国家,状态,失败码,失败原因,状态时间");
        assertThat(lines[1]).isEqualTo(
                "\"=\"\"+628123456789\"\"\",ID,\"=\"\"+12025550123\"\"\",US,失败,"
                        + "NOT_REGISTERED,号码未注册,1970-01-01 08:00:00");
    }

    private static List<String> componentNames(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
