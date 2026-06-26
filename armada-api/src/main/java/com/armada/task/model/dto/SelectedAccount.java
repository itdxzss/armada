package com.armada.task.model.dto;

/** 建任务时前端传入的选中账号(id 权威 + 号码串展示双填)。 */
public record SelectedAccount(
        /** 账号 id,权威。 */
        Long accountId,
        /** 账号号码,展示双填。 */
        String phone) {
}
