package com.armada.group.model.vo;

/** 手动刷新群详情的异步受理结果。 */
public record GroupMetadataSyncAcceptedVO(boolean accepted, String status) {
}
