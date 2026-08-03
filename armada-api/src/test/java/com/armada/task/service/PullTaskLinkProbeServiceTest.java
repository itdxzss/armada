package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.group.service.GroupInvitePageFetcher;
import com.armada.group.service.GroupInvitePageMetadata;
import com.armada.group.service.GroupInvitePageProbe;
import com.armada.shared.exception.BusinessException;
import com.armada.task.model.enums.PullTaskStandardLinkLineStatus;
import com.armada.task.service.PullTaskLinkProbeService.LinkLine;
import com.armada.task.service.PullTaskLinkProbeService.ProbeResult;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 群链接六态判定测试；抓取端口全部 mock，不出网。 */
class PullTaskLinkProbeServiceTest {

    private static final String CODE_A = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final String CODE_B = "BBBBBBBBBBBBBBBBBBBBBB";
    private static final String LINK_A = "chat.whatsapp.com/" + CODE_A;
    private static final String LINK_B = "chat.whatsapp.com/" + CODE_B;

    private GroupInvitePageFetcher fetcher;
    private PullTaskLinkProbeService service;

    @BeforeEach
    void setUp() {
        fetcher = mock(GroupInvitePageFetcher.class);
        // 同步执行器让并发路径在测试里变确定；生产注入有界线程池。
        service = new PullTaskLinkProbeService(fetcher, Runnable::run);
    }

    @Test
    void marksValidWhenPageReachableWithProfile() {
        stubProfile(LINK_A);

        ProbeResult result = service.probe("https://" + LINK_A, Set.of());

        assertThat(result.lines()).singleElement().satisfies(line -> {
            assertThat(line.lineNo()).isEqualTo(1);
            assertThat(line.normalizedLink()).isEqualTo(LINK_A);
            assertThat(line.status()).isEqualTo(PullTaskStandardLinkLineStatus.VALID);
        });
        assertThat(result.poolLinks()).containsExactly(LINK_A);
    }

    @Test
    void marksExpiredAndKeepsItOutOfThePool() {
        stubNoProfile(LINK_A);

        ProbeResult result = service.probe(LINK_A, Set.of());

        assertThat(result.lines().get(0).status())
                .isEqualTo(PullTaskStandardLinkLineStatus.LINK_EXPIRED);
        assertThat(result.poolLinks()).isEmpty();
    }

    @Test
    void marksProbeIncompleteButStillEntersThePool() {
        stubUnreachable(LINK_A);

        ProbeResult result = service.probe(LINK_A, Set.of());

        // 抓不到可能只是本系统网络抖动，启动时还会再校验一次，不能当成用户链接失效。
        assertThat(result.lines().get(0).status())
                .isEqualTo(PullTaskStandardLinkLineStatus.PROBE_INCOMPLETE);
        assertThat(result.poolLinks()).containsExactly(LINK_A);
    }

    @Test
    void reportsInvalidFormatWithOriginalLineNumberAndSkipsFetch() {
        stubProfile(LINK_A);

        ProbeResult result = service.probe("不是链接\n" + LINK_A, Set.of());

        assertThat(result.lines()).extracting(LinkLine::lineNo, LinkLine::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                1, PullTaskStandardLinkLineStatus.INVALID_FORMAT),
                        org.assertj.core.groups.Tuple.tuple(
                                2, PullTaskStandardLinkLineStatus.VALID));
        assertThat(result.lines().get(0).reason()).isNotBlank();
        verify(fetcher, times(1)).probe(anyString());
    }

    @Test
    void ignoresBlankLinesWithoutConsumingLineNumbers() {
        stubProfile(LINK_A);

        ProbeResult result = service.probe("\n\n" + LINK_A, Set.of());

        assertThat(result.lines()).singleElement()
                .satisfies(line -> assertThat(line.lineNo()).isEqualTo(3));
    }

    @Test
    void marksBatchDuplicateAndFetchesOnlyOnce() {
        stubProfile(LINK_A);

        ProbeResult result = service.probe(LINK_A + "\nhttps://" + LINK_A + "/", Set.of());

        assertThat(result.lines()).extracting(LinkLine::lineNo, LinkLine::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                1, PullTaskStandardLinkLineStatus.VALID),
                        org.assertj.core.groups.Tuple.tuple(
                                2, PullTaskStandardLinkLineStatus.DUPLICATE));
        assertThat(result.poolLinks()).containsExactly(LINK_A);
        verify(fetcher, times(1)).probe(LINK_A);
    }

    @Test
    void marksOccupiedWithoutFetchingAtAll() {
        stubProfile(LINK_B);

        ProbeResult result = service.probe(LINK_A + "\n" + LINK_B, Set.of(LINK_A));

        assertThat(result.lines()).extracting(LinkLine::status)
                .containsExactly(PullTaskStandardLinkLineStatus.OCCUPIED,
                        PullTaskStandardLinkLineStatus.VALID);
        assertThat(result.poolLinks()).containsExactly(LINK_B);
        verify(fetcher, never()).probe(LINK_A);
    }

    @Test
    void rejectsMoreThanTwoHundredUniqueLinks() {
        String text = IntStream.range(0, PullTaskLinkProbeService.MAX_VALID_LINK_COUNT + 1)
                .mapToObj(index -> "chat.whatsapp.com/" + String.format("%022d", index))
                .collect(Collectors.joining("\n"));

        assertThatThrownBy(() -> service.probe(text, Set.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(String.valueOf(PullTaskLinkProbeService.MAX_VALID_LINK_COUNT));
        verify(fetcher, never()).probe(anyString());
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

    private void stubProfile(String normalizedLink) {
        when(fetcher.probe(normalizedLink)).thenReturn(new GroupInvitePageProbe(
                new GroupInvitePageMetadata(inviteCode(normalizedLink), "真实群名", null), true));
    }

    private void stubNoProfile(String normalizedLink) {
        when(fetcher.probe(normalizedLink)).thenReturn(new GroupInvitePageProbe(
                new GroupInvitePageMetadata(inviteCode(normalizedLink), null, null), true));
    }

    private void stubUnreachable(String normalizedLink) {
        when(fetcher.probe(normalizedLink)).thenReturn(new GroupInvitePageProbe(
                new GroupInvitePageMetadata(inviteCode(normalizedLink), null, null), false));
    }

    private static String inviteCode(String normalizedLink) {
        return normalizedLink.substring(normalizedLink.lastIndexOf('/') + 1);
    }
}
