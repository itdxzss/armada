package com.armada.task.model.vo;

/**
 * 普通群链接任务提交冻结后的结果。
 *
 * <p>刻意不复用 {@code PullTaskListVO}：那是含营销与执行统计的一级列表行，
 * 创建这一刻没有任何执行事实，填它只能塞假零值。前端拿到 id 后走列表接口取完整行。</p>
 *
 * @param id                任务 ID
 * @param taskName          任务名称
 * @param status            当前状态，正常为 {@code WAIT_START}
 * @param groupCount        执行行数
 * @param expectedPullCount 全部执行行的有效料子数之和
 */
public record PullTaskStandardCreatedVO(Long id, String taskName, String status,
                                        Integer groupCount, Integer expectedPullCount) {
}
