package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.armada.task.service.PullTaskLinkMatcher.MatchResult;
import com.armada.task.service.PullTaskLinkMatcher.Pairing;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 群链接与 TXT 不放回随机匹配测试。 */
class PullTaskLinkMatcherTest {

    private static final List<String> FOUR_LINKS = List.of(
            "chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA",
            "chat.whatsapp.com/BBBBBBBBBBBBBBBBBBBBBB",
            "chat.whatsapp.com/CCCCCCCCCCCCCCCCCCCCCC",
            "chat.whatsapp.com/DDDDDDDDDDDDDDDDDDDDDD");

    /** 10 条互不相同的链接，用于验证链接侧确实被打乱（10! 种排列下恒等的概率仅 1/3628800）。 */
    private static final List<String> TEN_LINKS = List.of(
            "chat.whatsapp.com/LINK0000000000000000000",
            "chat.whatsapp.com/LINK1111111111111111111",
            "chat.whatsapp.com/LINK2222222222222222222",
            "chat.whatsapp.com/LINK3333333333333333333",
            "chat.whatsapp.com/LINK4444444444444444444",
            "chat.whatsapp.com/LINK5555555555555555555",
            "chat.whatsapp.com/LINK6666666666666666666",
            "chat.whatsapp.com/LINK7777777777777777777",
            "chat.whatsapp.com/LINK8888888888888888888",
            "chat.whatsapp.com/LINK9999999999999999999");

    @Test
    void pairsMinimumOfBothSidesAndLeavesTheRestUnmatched() {
        MatchResult result = PullTaskLinkMatcher.match(
                FOUR_LINKS, List.of("a.txt", "b.txt"), 1, new Random(42L));

        assertThat(result.pairings()).hasSize(2);
        assertThat(result.unmatchedLinks()).hasSize(2);
        assertThat(result.unmatchedFileKeys()).isEmpty();
    }

    @Test
    void ignoresTrailingFilesWhenLinksRunOut() {
        MatchResult result = PullTaskLinkMatcher.match(
                List.of(FOUR_LINKS.get(0)), List.of("a.txt", "b.txt", "c.txt"), 1, new Random(42L));

        assertThat(result.pairings()).hasSize(1);
        assertThat(result.pairings().get(0).fileKey()).isEqualTo("a.txt");
        // 被忽略的是尾部文件，顺序稳定，前端据此提示重发。
        assertThat(result.unmatchedFileKeys()).containsExactly("b.txt", "c.txt");
        assertThat(result.unmatchedLinks()).isEmpty();
    }

    @Test
    void assignsContinuousSeqStartingFromNextSeq() {
        MatchResult result = PullTaskLinkMatcher.match(
                FOUR_LINKS, List.of("a.txt", "b.txt", "c.txt"), 8, new Random(42L));

        assertThat(result.pairings()).extracting(Pairing::seq).containsExactly(8, 9, 10);
    }

    @Test
    void neverReusesALinkOrAFile() {
        MatchResult result = PullTaskLinkMatcher.match(
                FOUR_LINKS, List.of("a.txt", "b.txt", "c.txt", "d.txt"), 1, new Random(7L));

        assertThat(result.pairings()).extracting(Pairing::normalizedLink)
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(FOUR_LINKS);
        assertThat(result.pairings()).extracting(Pairing::fileKey)
                .containsExactlyInAnyOrder("a.txt", "b.txt", "c.txt", "d.txt");
    }

    @Test
    void unmatchedLinksAreExactlyThoseNotPaired() {
        MatchResult result = PullTaskLinkMatcher.match(
                FOUR_LINKS, List.of("a.txt"), 1, new Random(7L));

        String paired = result.pairings().get(0).normalizedLink();
        assertThat(result.unmatchedLinks()).hasSize(3).doesNotContain(paired);
        assertThat(result.unmatchedLinks()).allMatch(FOUR_LINKS::contains);
    }

    @Test
    void isReproducibleForTheSameSeed() {
        MatchResult first = PullTaskLinkMatcher.match(
                FOUR_LINKS, List.of("a.txt", "b.txt"), 1, new Random(2026L));
        MatchResult second = PullTaskLinkMatcher.match(
                FOUR_LINKS, List.of("a.txt", "b.txt"), 1, new Random(2026L));

        assertThat(first.pairings()).isEqualTo(second.pairings());
    }

    @Test
    void returnsEmptyResultWhenEitherSideIsEmpty() {
        assertThat(PullTaskLinkMatcher.match(List.of(), List.of("a.txt"), 1, new Random(1L))
                .pairings()).isEmpty();
        assertThat(PullTaskLinkMatcher.match(FOUR_LINKS, List.of(), 1, new Random(1L))
                .pairings()).isEmpty();
        assertThat(PullTaskLinkMatcher.match(FOUR_LINKS, List.of(), 1, new Random(1L))
                .unmatchedLinks()).hasSize(4);
    }

    @Test
    void shufflesLinkSideSoIdentityImplementationWouldFail() {
        // 固定种子下 10 条链接的配对顺序是确定的，不会 flaky；
        // 若实现把 Collections.shuffle 删掉（恒等排列），本断言必然失败，从而防止随机被悄悄退化。
        MatchResult result = PullTaskLinkMatcher.match(
                TEN_LINKS, TEN_LINKS, 1, new Random(42L));

        List<String> pairedOrder = result.pairings().stream()
                .map(Pairing::normalizedLink)
                .collect(Collectors.toList());

        assertThat(pairedOrder).hasSize(TEN_LINKS.size());
        assertThat(pairedOrder).isNotEqualTo(TEN_LINKS);
    }

    @Test
    void throwsNullPointerExceptionWithReadableMessageForNullArguments() {
        assertThatNullPointerException()
                .isThrownBy(() -> PullTaskLinkMatcher.match(null, List.of("a.txt"), 1, new Random(1L)))
                .withMessage("remainingLinks must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> PullTaskLinkMatcher.match(FOUR_LINKS, null, 1, new Random(1L)))
                .withMessage("incomingFileKeys must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> PullTaskLinkMatcher.match(FOUR_LINKS, List.of("a.txt"), 1, null))
                .withMessage("random must not be null");
    }
}
