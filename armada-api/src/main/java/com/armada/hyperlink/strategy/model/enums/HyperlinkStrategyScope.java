package com.armada.hyperlink.strategy.model.enums;

/** 统一策略表中的用途边界。 */
public enum HyperlinkStrategyScope {
    TEMPLATE(1),
    TASK_SNAPSHOT(2);

    private final int code;

    HyperlinkStrategyScope(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
