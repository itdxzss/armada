package com.armada.hyperlink.data.model.vo;

/** 按互斥号码池状态聚合的内部校准投影。 */
public class DataPackageStatusCountRow {

    /** 号码池状态码。 */
    private Integer poolStatus;
    /** 该状态当前行数。 */
    private Integer rowCount;

    public Integer getPoolStatus() { return poolStatus; }
    public void setPoolStatus(Integer poolStatus) { this.poolStatus = poolStatus; }
    public Integer getRowCount() { return rowCount; }
    public void setRowCount(Integer rowCount) { this.rowCount = rowCount; }
}
