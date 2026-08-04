package com.armada.task.scheduler;

/**
 * 单轮普通群链接执行调度统计。
 *
 * @param claimed  实际读回的有效租约行数
 * @param started  获得父任务并发槽位的行数
 * @param outcomes 各类处理结果
 */
public record PullTaskExecutionDispatchStats(int claimed, int started, Outcomes outcomes) {

    /**
     * 单轮处理结果分布。
     *
     * @param advanced 推进到下一阶段的行数
     * @param failed   明确失败的行数
     * @param deferred 延后重试的行数
     * @param skipped  状态竞争、槽位不足或失去租约的行数
     */
    public record Outcomes(int advanced, int failed, int deferred, int skipped) {

        private static Outcomes empty() {
            return new Outcomes(0, 0, 0, 0);
        }

        private Outcomes add(PullTaskExecutionDispatchResult result) {
            return switch (result) {
                case ADVANCED -> new Outcomes(advanced + 1, failed, deferred, skipped);
                case FAILED -> new Outcomes(advanced, failed + 1, deferred, skipped);
                case DEFERRED -> new Outcomes(advanced, failed, deferred + 1, skipped);
                case LOST -> skip();
            };
        }

        private Outcomes skip() {
            return new Outcomes(advanced, failed, deferred, skipped + 1);
        }
    }

    /** @return 全零统计 */
    public static PullTaskExecutionDispatchStats empty() {
        return new PullTaskExecutionDispatchStats(0, 0, Outcomes.empty());
    }

    /**
     * 累加一条已获得槽位的处理结果。
     *
     * @param result 单行处理结果
     * @return 新统计值
     */
    public PullTaskExecutionDispatchStats add(PullTaskExecutionDispatchResult result) {
        return new PullTaskExecutionDispatchStats(claimed, started + 1, outcomes.add(result));
    }

    /** @return 增加一条未获得槽位或处理异常的跳过记录 */
    public PullTaskExecutionDispatchStats skip() {
        return new PullTaskExecutionDispatchStats(claimed, started, outcomes.skip());
    }

    /** @return 设置本轮实际读回的租约行数 */
    public PullTaskExecutionDispatchStats withClaimed(int value) {
        return new PullTaskExecutionDispatchStats(value, started, outcomes);
    }

    /** @return 推进到下一阶段的行数 */
    public int advanced() {
        return outcomes.advanced();
    }

    /** @return 明确失败的行数 */
    public int failed() {
        return outcomes.failed();
    }

    /** @return 延后重试的行数 */
    public int deferred() {
        return outcomes.deferred();
    }

    /** @return 状态竞争、槽位不足或失去租约的行数 */
    public int skipped() {
        return outcomes.skipped();
    }
}
