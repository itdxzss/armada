package com.armada.contact.task.model.dto;

import java.util.List;

/**
 * 通讯录任务批量删除请求，不接受客户端指定租户。
 *
 * @param ids 待删除任务 ID，1 至 200 个，服务层统一校验并去重
 */
public record ContactTaskBatchDeleteDTO(List<Long> ids) {
}
