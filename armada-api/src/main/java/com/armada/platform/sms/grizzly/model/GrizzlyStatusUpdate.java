package com.armada.platform.sms.grizzly.model;

/** 接码平台状态变更；不会向目标注册服务发送短信。 */
public enum GrizzlyStatusUpdate {
    /** 通知接码平台，目标服务已向该号码请求发送短信。 */
    READY("1", "ACCESS_READY"),
    /** 等待新码；调用方必须先确认该订单支持再次收码。 */
    REQUEST_ANOTHER_SMS("3", "ACCESS_RETRY_GET"),
    /** 确认本次接码流程完成。 */
    COMPLETE("6", "ACCESS_ACTIVATION"),
    /** 申请取消订单，能否退款由供应商订单状态决定。 */
    CANCEL("8", "ACCESS_CANCEL");

    /** 供应商要求的状态参数。 */
    private final String wireValue;
    /** 对应操作成功时要求的应答。 */
    private final String acknowledgement;

    GrizzlyStatusUpdate(String wireValue, String acknowledgement) {
        this.wireValue = wireValue;
        this.acknowledgement = acknowledgement;
    }

    /** @return 供应商 setStatus 的 status 参数 */
    public String wireValue() {
        return wireValue;
    }

    /** @return 该操作的已确认成功应答，不能混用其他状态的成功文本 */
    public String acknowledgement() {
        return acknowledgement;
    }
}
