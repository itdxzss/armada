package com.armada.platform.protocol.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/**
 * 协议命令 Outbox 状态码。
 *
 * <p>状态以 tinyint 存入 {@code protocol_command_outbox.status};publisher 只从 PENDING 抢占到
 * LOCKED,发送前再以 CAS 提交为 DISPATCHING。发送中业务结束则进入 CANCEL_REQUESTED，
 * 最后按 Kafka 发送结果流转到 SENT、PENDING(重试)、DEAD 或 CANCELED。</p>
 */
public enum ProtocolCommandOutboxStatus {

    /** 待发送,可被 publisher 扫描抢占。 */
    PENDING(0, "待发送"),

    /** 已被 publisher 预抢占，业务结束仍可取消。 */
    LOCKED(1, "已抢占"),

    /** Kafka producer ack 成功。 */
    SENT(2, "已发送"),

    /** 重试耗尽或不可恢复错误,进入死信态。 */
    DEAD(3, "死信"),

    /** 业务侧取消,不再发送。 */
    CANCELED(4, "已取消"),

    /** 发送权已提交，不再允许业务终态直接撤回。 */
    DISPATCHING(5, "发送已提交"),

    /** 业务已结束，当前发送允许收口，但失败后不得重试。 */
    CANCEL_REQUESTED(6, "等待发送收口");

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
