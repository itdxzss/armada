package com.armada.task.scheduler;

/** 补充拉手踩链接命令和实时在群复核结果。 */
public record PullTaskSupplementPullerOutcome(
        Kind kind,
        String reasonCode,
        String reasonMessage) {

    public enum Kind { CONFIRMED, FAILED, UNKNOWN }

    public static PullTaskSupplementPullerOutcome confirmed() {
        return new PullTaskSupplementPullerOutcome(Kind.CONFIRMED, null, null);
    }

    public static PullTaskSupplementPullerOutcome failed(String reasonCode) {
        return new PullTaskSupplementPullerOutcome(
                Kind.FAILED, reasonCode, "补充拉手踩链接失败");
    }

    public static PullTaskSupplementPullerOutcome unknown(String reasonCode) {
        return new PullTaskSupplementPullerOutcome(
                Kind.UNKNOWN, reasonCode, "补充拉手在群结果无法确认");
    }
}
