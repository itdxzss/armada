package com.armada.hyperlink.task.model.enums;

/** 超链任务计费适配模式。 */
public enum HyperlinkBillingMode {

    /** 未接真实钱包时保持任务启用门禁关闭。 */
    UNAVAILABLE,

    /** 仅供测试环境跑通业务闭环的显式零计费模式。 */
    ZERO_TEST;

    /**
     * 解析运行配置，非法值直接阻止应用启动，避免误用未知计费模式。
     *
     * @param configuredMode 配置值
     * @return 已确认的计费模式
     */
    public static HyperlinkBillingMode fromProperty(String configuredMode) {
        String normalized = configuredMode == null ? "" : configuredMode.trim();
        if (normalized.isEmpty()) {
            return UNAVAILABLE;
        }
        return valueOf(normalized.toUpperCase(java.util.Locale.ROOT));
    }
}
