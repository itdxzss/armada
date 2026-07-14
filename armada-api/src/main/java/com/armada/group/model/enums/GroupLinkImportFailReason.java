package com.armada.group.model.enums;

/** 群链接导入失败原因常量。 */
public final class GroupLinkImportFailReason {

    /** 批内重复或已经进入导入链接分组。 */
    public static final String DUPLICATE = "重复";

    /** 原始行中没有合法的 WhatsApp 群邀请链接。 */
    public static final String FORMAT_ERROR = "格式错误";

    /** WhatsApp 公开邀请页未返回真实群名。 */
    public static final String LINK_INVALID = "链接失效";

    private GroupLinkImportFailReason() {
    }
}
