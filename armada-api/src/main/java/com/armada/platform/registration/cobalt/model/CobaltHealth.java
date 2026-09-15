package com.armada.platform.registration.cobalt.model;

/** @param registrationEnabled 是否开启真实注册 @param availableSlots 当前可接收的新会话数量 */
public record CobaltHealth(boolean registrationEnabled, int availableSlots) { }
