package com.armada.hyperlink.task.model.vo;

/** 账号范围弹框的可用账号数和协议容量。 */
public record HyperlinkAccountMatchCountVO(
        int availableAccountCount,
        int protocolCount,
        int maxConcurrentNum) {
}
