package com.armada.platform.protocol.model.enums;

/** WhatsApp 群主身份类型。 */
public enum OwnerIdentityKind {

    /** 已确认的手机号身份，可以安全提取国际手机号。 */
    PN,

    /** WhatsApp 内部 LID 身份，不是手机号。 */
    LID,

    /** 响应缺字段、格式异常或 mode 与 JID 后缀冲突，不能确认身份。 */
    UNKNOWN
}
