package com.armada.task.model.dto;

/** 条件更新要写入的协议事实结果。 */
public record PullTaskFactResult(
        String reasonCode,
        String reasonMessage,
        String resultJid,
        Long occurredAt) {

    /** @return 不带附加字段的结果 */
    public static PullTaskFactResult empty() {
        return new PullTaskFactResult(null, null, null, null);
    }

    /** @return 带成功成员身份和发生时间的结果 */
    public static PullTaskFactResult success(String resultJid, long occurredAt) {
        return new PullTaskFactResult(null, null, resultJid, occurredAt);
    }

    /** @return 带稳定原因和脱敏描述的结果 */
    public static PullTaskFactResult reason(String code, String message) {
        return new PullTaskFactResult(code, message, null, null);
    }
}
