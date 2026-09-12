package com.armada.platform.protocol.model.command;

/** 后端已按租户、任务和群解析的原消息；业务表单不能直接指定此结构。 */
public record MessageReply(String messageId, String groupJid, MessageQuoteContext context) { }
