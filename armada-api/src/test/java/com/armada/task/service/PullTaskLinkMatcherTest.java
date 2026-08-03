package com.armada.task.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.task.service.PullTaskLinkMatcher.MatchResult;
import com.armada.task.service.PullTaskLinkMatcher.Pairing;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** 群链接与 TXT 不放回随机匹配测试。 */
class PullTaskLinkMatcherTest {

    private static final List<String> FOUR_LINKS = List.of(
            "chat.whatsapp.com/AAAAAAAAAAAAAAAAAAAAAA",
            "chat.whatsapp.com/BBBBBBBBBBBBBBBBBBBBBB",
            "chat.whatsapp.com/CCCCCCCCCCCCCCCCCCCCCC",
            "chat.whatsapp.com/DDDDDDDDDDDDDDDDDDDDDD");

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
}
