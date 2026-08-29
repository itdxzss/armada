package com.armada.hyperlink.task.model.dto;

import com.armada.hyperlink.task.model.enums.HyperlinkTaskAction;

/** 生命周期动作请求。 */
public record HyperlinkTaskActionDTO(
        HyperlinkTaskAction action,
        Integer version,
        String quoteToken) {
}
