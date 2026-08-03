package com.armada.task.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 群链接与 TXT 的不放回一对一随机匹配。
 *
 * <p>纯函数、无 Spring 依赖、无状态。增量语义由调用方保证：调用方只传尚未成行的链接，
 * 因此本类不会扰动已经落库的执行行。</p>
 *
 * <p>随机只作用在链接侧——打乱链接后取前 k 个，与保持上传顺序的前 k 个文件配对，
 * 这已经是一个均匀随机的一对一映射；两侧都打乱不会提高随机性，只会让"被忽略的是哪些文件"
 * 变得不可预期。</p>
 */
public final class PullTaskLinkMatcher {

    private PullTaskLinkMatcher() {
    }

    /**
     * 在剩余链接与本次有效 TXT 之间做不放回一对一随机匹配。
     *
     * @param remainingLinks  尚未成行的归一化链接，调用方保证已去重且顺序稳定
     * @param incomingFileKeys 本次有效 TXT 的标识，按上传顺序
     * @param nextSeq         本批第一条执行行的 seq
     * @param random          随机源；生产传 {@code ThreadLocalRandom.current()}，测试传固定种子
     * @return 本批配对、未匹配链接与被忽略的尾部文件
     */
    public static MatchResult match(List<String> remainingLinks, List<String> incomingFileKeys,
                                    int nextSeq, Random random) {
        List<String> shuffledLinks = new ArrayList<>(remainingLinks);
        Collections.shuffle(shuffledLinks, random);

        int pairCount = Math.min(shuffledLinks.size(), incomingFileKeys.size());
        List<Pairing> pairings = new ArrayList<>(pairCount);
        for (int index = 0; index < pairCount; index++) {
            pairings.add(new Pairing(
                    nextSeq + index, shuffledLinks.get(index), incomingFileKeys.get(index)));
        }

        List<String> unmatchedLinks = List.copyOf(
                shuffledLinks.subList(pairCount, shuffledLinks.size()));
        List<String> unmatchedFileKeys = List.copyOf(
                incomingFileKeys.subList(pairCount, incomingFileKeys.size()));
        return new MatchResult(List.copyOf(pairings), unmatchedLinks, unmatchedFileKeys);
    }

    /**
     * 一条冻结的群链接与 TXT 配对。
     *
     * @param seq            任务内展示与执行顺序
     * @param normalizedLink 归一化群链接
     * @param fileKey        TXT 标识，由调用方决定（本切片用原始文件名 + 上传序号）
     */
    public record Pairing(int seq, String normalizedLink, String fileKey) {
    }

    /**
     * 一次匹配的完整产出。
     *
     * @param pairings          本批新增的配对
     * @param unmatchedLinks    未匹配的剩余链接
     * @param unmatchedFileKeys 因链接不足被忽略的尾部文件
     */
    public record MatchResult(List<Pairing> pairings, List<String> unmatchedLinks,
                              List<String> unmatchedFileKeys) {
    }
}
