package com.armada.group.model.enums;

/** 群详情耐久同步任务状态。 */
public enum GroupMetadataSyncStatus {
    PENDING(1),
    RUNNING(2),
    RETRY_WAIT(3),
    SUCCEEDED(4),
    DEFERRED(5),
    FAILED(6);

    private final int code;

    GroupMetadataSyncStatus(int code) {
        this.code = code;
    }

    /** 返回稳定数据库码。 */
    public int code() {
        return code;
    }

    /**
     * 按稳定数据库码解析任务状态。
     *
     * @param code 数据库码
     * @return 对应状态
     * @throws IllegalArgumentException 未知码
     */
    public static GroupMetadataSyncStatus fromCode(Integer code) {
        if (code != null) {
            for (GroupMetadataSyncStatus status : values()) {
                if (status.code == code) {
                    return status;
                }
            }
        }
        throw new IllegalArgumentException("未知群详情同步状态: " + code);
    }
}
