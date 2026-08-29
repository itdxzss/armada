package com.armada.hyperlink.task.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 生成不超过数据库列长、且覆盖外部预约号、任务和版本的稳定钱包幂等键。 */
final class HyperlinkBillingOperationKeys {
    private HyperlinkBillingOperationKeys() { }

    static String create(String action, String externalReservationNo, long taskId, int version) {
        String source = externalReservationNo + ":" + taskId + ":" + version;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return "hl:" + action + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }
}
