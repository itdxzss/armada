package com.armada.hyperlink.task.model.vo;

/** 查询时由 first_visit_at 动态聚合出的 UV 桶。 */
public class HyperlinkVisitBucketRow {
    private Integer bucketNo;
    private Long newUv;
    public Integer getBucketNo() { return bucketNo; }
    public void setBucketNo(Integer value) { this.bucketNo = value; }
    public Long getNewUv() { return newUv; }
    public void setNewUv(Long value) { this.newUv = value; }
}
