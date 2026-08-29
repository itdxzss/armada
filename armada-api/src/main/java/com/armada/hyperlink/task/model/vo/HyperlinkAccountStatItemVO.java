package com.armada.hyperlink.task.model.vo;

import java.math.BigDecimal;

/** 发信账号维度统计分页行。 */
public record HyperlinkAccountStatItemVO(
        long bucketKey,
        Long accountId,
        String senderPhone,
        String senderCountryIso2,
        String accountType,
        BigDecimal retentionDays,
        long successNum,
        long deliveredNum,
        long failedNum,
        Long lastSendAt) {
}
