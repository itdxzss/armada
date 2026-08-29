package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.model.enums.HyperlinkRecipientStatus;
import org.springframework.stereotype.Component;

/** recipient 结果和 ACK 的单调状态归并器。 */
@Component
public class HyperlinkRecipientStateMachine {

    /** 终态失败保持不变，其余 ACK 只允许按等级向前。 */
    public HyperlinkRecipientStatus advance(
            HyperlinkRecipientStatus current,
            HyperlinkRecipientStatus incoming) {
        if (current.terminalFailure()) {
            return current;
        }
        if (incoming.terminalFailure()) {
            return current == HyperlinkRecipientStatus.PENDING
                    || current == HyperlinkRecipientStatus.SENDING ? incoming : current;
        }
        return incoming.rank() > current.rank() ? incoming : current;
    }
}
