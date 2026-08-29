package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.model.entity.HyperlinkTaskContent;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.Set;

/** 发送合同硬门禁：只允许已经由 Web/Android 私聊适配器真实实现的内容语义。 */
public final class HyperlinkMessageDeliveryGuard {
    private static final Set<Integer> SUPPORTED_TYPES = Set.of(1, 3, 4);

    private HyperlinkMessageDeliveryGuard() { }

    public static void requireSupported(HyperlinkTaskContent content) {
        if (content == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "超链任务内容不存在");
        }
        if (Integer.valueOf(2).equals(content.getMessageType())) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "历史双图文缺少 Web/Android 真实协议发送支持，禁止启动或发送");
        }
        if (!SUPPORTED_TYPES.contains(content.getMessageType())) {
            throw new BusinessException(ErrorCode.VALIDATION, "超链消息类型不支持发送");
        }
    }
}
