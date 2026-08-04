package com.armada.task.model.vo;

import java.util.List;

/**
 * 创建页的完整草稿视图。
 *
 * <p>链接框文本不落库，因此回读草稿时 {@code linkLines} 与 {@code fileResults} 必然为空，
 * 只有本次请求才会带上它们；前端需要自行用 sessionStorage 恢复链接框内容。</p>
 *
 * @param draftTaskId        草稿任务 ID；用户还没有草稿时为 null
 * @param version            草稿任务乐观锁版本，提交时原样回传
 * @param rows               已冻结的执行行，按 seq 升序
 * @param linkLines          本次请求的链接逐行判定结果
 * @param fileResults        本次请求的 TXT 逐文件解析结果
 * @param matchedCount       已匹配执行行总数
 * @param remainingLinkCount 本次请求后仍未匹配的有效链接数
 * @param ignoredFileCount   本次因剩余链接不足被忽略的文件数
 */
public record PullTaskStandardDraftVO(Long draftTaskId, Integer version,
                                      List<PullTaskStandardExecutionRowVO> rows,
                                      List<PullTaskStandardLinkLineVO> linkLines,
                                      List<PullTaskStandardFileResultVO> fileResults,
                                      int matchedCount, int remainingLinkCount,
                                      int ignoredFileCount) {
}
