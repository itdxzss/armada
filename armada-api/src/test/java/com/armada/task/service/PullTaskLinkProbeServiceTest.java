package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.shared.exception.BusinessException;
import com.armada.task.model.enums.PullTaskStandardLinkLineStatus;
import com.armada.task.service.PullTaskLinkProbeService.LinkLine;
import com.armada.task.service.PullTaskLinkProbeService.ProbeResult;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** 群链接创建计划的本地格式、去重和占用判定测试。 */
class PullTaskLinkProbeServiceTest {

    private static final String CODE_A = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final String CODE_B = "BBBBBBBBBBBBBBBBBBBBBB";
    private static final String LINK_A = "chat.whatsapp.com/" + CODE_A;
    private static final String LINK_B = "chat.whatsapp.com/" + CODE_B;

    private final PullTaskLinkProbeService service = new PullTaskLinkProbeService();

    @Test
    void marksFormatValidWithoutRequestingThePublicInvitePage() {
        ProbeResult result = service.probe("https://" + LINK_A, Set.of());

        assertThat(result.lines()).singleElement().satisfies(line -> {
            assertThat(line.lineNo()).isEqualTo(1);
            assertThat(line.normalizedLink()).isEqualTo(LINK_A);
            assertThat(line.status()).isEqualTo(PullTaskStandardLinkLineStatus.VALID);
        });
        assertThat(result.poolLinks()).containsExactly(LINK_A);
    }

    @Test
    void reportsInvalidFormatWithOriginalLineNumber() {
        ProbeResult result = service.probe("不是链接\n" + LINK_A, Set.of());

        assertThat(result.lines()).extracting(LinkLine::lineNo, LinkLine::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                1, PullTaskStandardLinkLineStatus.INVALID_FORMAT),
                        org.assertj.core.groups.Tuple.tuple(
                                2, PullTaskStandardLinkLineStatus.VALID));
        assertThat(result.lines().get(0).reason()).isNotBlank();
    }

    @Test
    void ignoresBlankLinesWithoutConsumingLineNumbers() {
        ProbeResult result = service.probe("\n\n" + LINK_A, Set.of());

        assertThat(result.lines()).singleElement()
                .satisfies(line -> assertThat(line.lineNo()).isEqualTo(3));
    }

    @Test
    void marksBatchDuplicateAndKeepsOnlyTheFirstLink() {
        ProbeResult result = service.probe(LINK_A + "\nhttps://" + LINK_A + "/", Set.of());

        assertThat(result.lines()).extracting(LinkLine::lineNo, LinkLine::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                1, PullTaskStandardLinkLineStatus.VALID),
                        org.assertj.core.groups.Tuple.tuple(
                                2, PullTaskStandardLinkLineStatus.DUPLICATE));
        assertThat(result.poolLinks()).containsExactly(LINK_A);
    }

    @Test
    void marksOccupiedAndKeepsOtherFormatValidLinks() {
        ProbeResult result = service.probe(LINK_A + "\n" + LINK_B, Set.of(LINK_A));

        assertThat(result.lines()).extracting(LinkLine::status)
                .containsExactly(PullTaskStandardLinkLineStatus.OCCUPIED,
                        PullTaskStandardLinkLineStatus.VALID);
        assertThat(result.poolLinks()).containsExactly(LINK_B);
    }

    @Test
    void rejectsMoreThanTwoHundredUniqueLinks() {
        String text = IntStream.range(0, PullTaskLinkProbeService.MAX_VALID_LINK_COUNT + 1)
                .mapToObj(index -> "chat.whatsapp.com/" + String.format("%022d", index))
                .collect(Collectors.joining("\n"));

        assertThatThrownBy(() -> service.probe(text, Set.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(String.valueOf(PullTaskLinkProbeService.MAX_VALID_LINK_COUNT));
    }

    @Test
    void returnsEmptyResultForBlankText() {
        ProbeResult result = service.probe("   ", Set.of());

        assertThat(result.lines()).isEmpty();
        assertThat(result.poolLinks()).isEmpty();
    }

    @Test
    void candidateLinksNormalizesOperationalTextAndDeduplicates() {
        // 带序号、说明文字和查询串的运营文本必须被识别；严格整行匹配会漏掉它们。
        String text = "1. https://" + LINK_A + "?x=1 群名\n" + LINK_A + "\n不是链接\n" + LINK_B;

        assertThat(PullTaskLinkProbeService.candidateLinks(text))
                .containsExactly(LINK_A, LINK_B);
    }

    @Test
    void candidateLinksIsEmptyForBlankText() {
        assertThat(PullTaskLinkProbeService.candidateLinks(null)).isEmpty();
        assertThat(PullTaskLinkProbeService.candidateLinks("  ")).isEmpty();
    }

}
