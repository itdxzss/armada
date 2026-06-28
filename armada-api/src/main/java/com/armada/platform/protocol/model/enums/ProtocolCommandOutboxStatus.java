package com.armada.platform.protocol.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/**
 * 协议命令 Outbox 状态码。
 *
 * <p>状态以 tinyint 存入 {@code protocol_command_outbox.status};publisher 只从 PENDING 抢占到
 * LOCKED,再按 Kafka 发送结果流转到 SENT、PENDING(重试) 或 DEAD。</p>
 */
public enum ProtocolCommandOutboxStatus {

    /** 待发送,可被 publisher 扫描抢占。 */
    PENDING(0, "待发送"),

    /** 已被 publisher 抢占,事务外发送 Kafka。 */
    LOCKED(1, "发送中"),

    /** Kafka producer ack 成功。 */
    SENT(2, "已发送"),

    /** 重试耗尽或不可恢复错误,进入死信态。 */
    DEAD(3, "死信"),

    /** 业务侧取消,不再发送。 */
    CANCELED(4, "已取消");

    private final int code;
    private final String label;

    ProtocolCommandOutboxStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 获取落库状态码。
     *
     * @return tinyint 状态码
     */
    public int code() {
        return code;
    }

    /**
     * 获取中文展示名。
     *
     * @return 中文状态名
     */
    public String label() {
        return label;
    }

    /**
     * 按落库状态码反查状态枚举。
     *
     * @param code tinyint 状态码
     * @return 对应状态枚举
     * @throws BusinessException 状态码为空或非法时抛 VALIDATION
     */
    public static ProtocolCommandOutboxStatus fromCode(Integer code) {
        if (code != null) {
            for (ProtocolCommandOutboxStatus status : values()) {
                if (status.code == code) {
                    return status;
                }
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "非法的协议命令 Outbox 状态: " + code);
    }
}
