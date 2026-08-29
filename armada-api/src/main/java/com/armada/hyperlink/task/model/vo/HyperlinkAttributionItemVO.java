package com.armada.hyperlink.task.model.vo;

import java.util.List;

/** 一个点击 recipient 的首触归因事实。 */
public record HyperlinkAttributionItemVO(
        long id,
        String recipientPhone,
        String senderPhone,
        int visitCount,
        String countryIso2,
        String device,
        String os,
        String browser,
        String language,
        String ip,
        String userAgent,
        Long firstVisitAt,
        Long lastVisitAt,
        boolean attributionPurged,
        boolean sensitiveVisible,
        List<String> maskedFields) { }
