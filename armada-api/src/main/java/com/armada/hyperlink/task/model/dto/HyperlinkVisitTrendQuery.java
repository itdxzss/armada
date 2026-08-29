package com.armada.hyperlink.task.model.dto;

/** 首个 UV 起算的访问趋势参数。 */
public class HyperlinkVisitTrendQuery {
    private String range = "24h";
    private String granularity = "30m";

    public String getRange() {
        return switch (range == null ? "" : range.toLowerCase()) {
            case "12h", "24h", "36h", "48h", "72h" -> range.toLowerCase();
            default -> "24h";
        };
    }
    public void setRange(String value) { this.range = value; }
    public String getGranularity() {
        return switch (granularity == null ? "" : granularity.toLowerCase()) {
            case "30m", "1h", "2h" -> granularity.toLowerCase();
            default -> "30m";
        };
    }
    public void setGranularity(String value) { this.granularity = value; }
}
