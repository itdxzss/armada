package com.armada.hyperlink.click;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.hyperlink.click.model.dto.HyperlinkClickAnalysisExportDTO;
import com.armada.hyperlink.click.model.dto.HyperlinkClickAnalysisQuery;
import com.armada.hyperlink.click.model.enums.HyperlinkClickAnalysisMode;
import com.armada.hyperlink.click.service.HyperlinkClickAnalysisService;
import com.armada.hyperlink.click.service.impl.HyperlinkClickAnalysisServiceImpl;
import com.armada.shared.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 点击事实写入方接入前的稳定分析合同、阈值与日期保护测试。 */
class HyperlinkClickAnalysisServiceTest {

    private final HyperlinkClickAnalysisService service =
            new HyperlinkClickAnalysisServiceImpl();

    @Test
    void emptyFactSourceStillReturnsEveryNormalizedThreshold() {
        HyperlinkClickAnalysisQuery query = new HyperlinkClickAnalysisQuery();
        query.setDateFrom(1_000L);
        query.setDateTo(2_000L);
        query.setThresholds("20,5,10,5");
        query.setDimension("recipient_country");
        query.setCountryIso2("ph");

        var result = service.analyze(HyperlinkClickAnalysisMode.NEVER_CLICK, query);

        assertThat(result.mode()).isEqualTo("never-click");
        assertThat(result.factSourceReady()).isFalse();
        assertThat(result.totalPhones()).isZero();
        assertThat(result.buckets())
                .extracting(bucket -> bucket.threshold())
                .containsExactly(5, 10, 20);
        assertThat(result.buckets())
                .allSatisfy(bucket -> {
                    assertThat(bucket.count()).isZero();
                    assertThat(bucket.percent()).isZero();
                });
        assertThat(result.countries()).isEmpty();
    }

    @Test
    void rejectsRangeOverNinetyDaysAndInvalidModeThresholds() {
        HyperlinkClickAnalysisQuery tooLong = new HyperlinkClickAnalysisQuery();
        tooLong.setDateFrom(1_000L);
        tooLong.setDateTo(1_000L + Duration.ofDays(91).toMillis());
        tooLong.setThresholds("5,10");
        assertThatThrownBy(() -> service.analyze(
                HyperlinkClickAnalysisMode.UV_RATIO, tooLong))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("90 天");

        HyperlinkClickAnalysisQuery decimalNever = new HyperlinkClickAnalysisQuery();
        decimalNever.setDateFrom(1_000L);
        decimalNever.setDateTo(2_000L);
        decimalNever.setThresholds("2.5");
        assertThatThrownBy(() -> service.analyze(
                HyperlinkClickAnalysisMode.NEVER_CLICK, decimalNever))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("整数");
    }

    @Test
    void perBucketExportIsARealEmptyTxtFileUntilClickFactsExist() {
        var request = new HyperlinkClickAnalysisExportDTO(
                1_000L, 2_000L, 10, "PH", "txt");

        var file = service.export(HyperlinkClickAnalysisMode.UV_RATIO, request);

        assertThat(file.filename()).contains("点击率高_10").endsWith(".txt");
        assertThat(file.contentType()).isEqualTo("text/plain;charset=UTF-8");
        assertThat(file.exportedCount()).isZero();
        assertThat(new String(file.bytes(), StandardCharsets.UTF_8)).isEmpty();
    }
}
