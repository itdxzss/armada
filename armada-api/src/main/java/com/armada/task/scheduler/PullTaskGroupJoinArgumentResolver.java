package com.armada.task.scheduler;

import com.armada.platform.protocol.model.enums.ProtocolBackend;

/** 把普通拉群冻结的标准链接转换为各协议后端要求的进群参数。 */
final class PullTaskGroupJoinArgumentResolver {

    private static final String HTTPS_SCHEME_PREFIX = "https://";

    private PullTaskGroupJoinArgumentResolver() {
    }

    /** Web 使用完整 HTTPS 链接，Android 使用大小写敏感的纯邀请码。 */
    static String resolve(
            ProtocolBackend backend, String normalizedLink, String inviteCode) {
        if (backend == null) {
            throw new IllegalArgumentException("协议后端不能为空");
        }
        return switch (backend) {
            case WEB -> HTTPS_SCHEME_PREFIX + requireText(normalizedLink, "标准群链接");
            case ANDROID -> requireText(inviteCode, "群邀请码");
        };
    }

    /** 把当前邀请码转换为协议后端要求的进群参数，不复用执行行中的冻结旧链接。 */
    static String resolveCurrentInviteCode(ProtocolBackend backend, String inviteCode) {
        String code = requireText(inviteCode, "群邀请码");
        return resolve(backend, "chat.whatsapp.com/" + code, code);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value.trim();
    }
}
