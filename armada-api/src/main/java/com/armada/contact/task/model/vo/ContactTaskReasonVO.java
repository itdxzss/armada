package com.armada.contact.task.model.vo;

/** 异常原因聚合；状态区分失败、未知和未执行跳过，不返回原始敏感日志。 */
public record ContactTaskReasonVO(String sendStatus, String errorCode, long count) {
}
