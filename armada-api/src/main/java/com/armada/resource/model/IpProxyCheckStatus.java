package com.armada.resource.model;

/**
 * IP 代理检测结果状态。{@code value} 返回给前端,表示本次检测是否成功。
 */
public enum IpProxyCheckStatus {

    /** 本次检测成功。 */
    SUCCESS("success"),
    /** 本次检测失败。 */
    FAILED("failed");

    private final String value;

    IpProxyCheckStatus(String value) {
        this.value = value;
    }

    /** 前端展示/判断使用的稳定值。 */
    public String value() {
        return value;
    }
}
