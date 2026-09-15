package com.armada.platform.sms.grizzly.model;

/**
 * 接码平台服务目录项。
 * @param code 用于购买和报价的服务代码
 * @param name 平台显示名称
 */
public record GrizzlyService(String code, String name) {
}
