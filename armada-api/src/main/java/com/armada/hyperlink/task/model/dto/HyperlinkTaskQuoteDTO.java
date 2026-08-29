package com.armada.hyperlink.task.model.dto;

/** CREATE 或 START 报价请求，互斥字段由服务端严格校验。 */
public record HyperlinkTaskQuoteDTO(
        String purpose,
        Long taskId,
        Long dataPackageId,
        String taskMode,
        Integer maxExecutingAccounts) {
}
