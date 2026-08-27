package com.armada.hyperlink.click.model.enums;

/** 超链点击分析的两个竞品口径。 */
public enum HyperlinkClickAnalysisMode {

    /** 成功发送达到阈值但从未点击。 */
    NEVER_CLICK("never-click", "从来不点"),

    /** 点击次数除以成功发送次数达到阈值。 */
    UV_RATIO("uv-ratio", "点击率高");

    private final String apiValue;
    private final String exportLabel;

    HyperlinkClickAnalysisMode(String apiValue, String exportLabel) {
        this.apiValue = apiValue;
        this.exportLabel = exportLabel;
    }

    public String apiValue() {
        return apiValue;
    }

    public String exportLabel() {
        return exportLabel;
    }
}
