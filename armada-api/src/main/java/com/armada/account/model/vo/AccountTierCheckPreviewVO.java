package com.armada.account.model.vo;
/** 冻结候选范围的预览。 */
public record AccountTierCheckPreviewVO(int eligible, int excluded, java.util.List<ExcludedReason> excludedReasons, String previewToken) {
    /** 排除原因及数量，不返回不可见账号身份。 */
    public record ExcludedReason(String reason, int count) {}
}
