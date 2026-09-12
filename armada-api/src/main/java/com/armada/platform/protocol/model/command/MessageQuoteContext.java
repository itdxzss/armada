package com.armada.platform.protocol.model.command;

import java.util.Base64;

/** 双协议通用的原消息引用快照；内容为裁剪后的 WA Message protobuf，不含账号凭据。 */
public record MessageQuoteContext(Integer version, String senderJid, String messageBase64) {
    /** 目前只支持第一版引用快照，大小受限以避免事件与 outbox 膨胀。 */
    public boolean valid() {
        if (!Integer.valueOf(1).equals(version) || senderJid == null
                || !senderJid.matches("[0-9]+@(s\\.whatsapp\\.net|lid)")
                || messageBase64 == null || messageBase64.isEmpty() || messageBase64.length() > 131072) return false;
        try { return Base64.getDecoder().decode(messageBase64).length > 0; }
        catch (IllegalArgumentException exception) { return false; }
    }
}
