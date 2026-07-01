package com.armada.resource.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/**
 * IP 代理检测生命周期状态。该状态描述最近一次检测任务,不直接表示是否可分配。
 */
public enum IpProxyCheckLifecycleStatus {

    /** 检测中:已提交后台任务,结果尚未落库。 */
    DETECTING(0, "检测中"),
    /** 检测通过:出口 IP 与 WhatsApp 连通性已确认。 */
    SUCCESS(1, "检测通过"),
    /** 检测失败:代理不可用或 WhatsApp 官方站点不通。 */
    FAILED(2, "检测失败");

    private final int code;
    private final String label;

    IpProxyCheckLifecycleStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public int code() {
        return code;
    }

    public String label() {
        return label;
    }

    /**
     * 由 tinyint 码转检测生命周期枚举。
     *
     * @param code 数据库存储码
     * @return 检测生命周期状态
     * @throws BusinessException 非法码值时抛出
     */
    public static IpProxyCheckLifecycleStatus fromCode(Integer code) {
        if (code != null) {
            for (IpProxyCheckLifecycleStatus v : values()) {
                if (v.code == code) {
                    return v;
                }
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION, "非法的代理检测状态: " + code);
    }

    /** 码值转中文 label;非法码原样返回字符串,避免历史脏数据影响展示。 */
    public static String labelOf(Integer code) {
        if (code != null) {
            for (IpProxyCheckLifecycleStatus v : values()) {
                if (v.code == code) {
                    return v.label;
                }
            }
        }
        return String.valueOf(code);
    }
}
