package com.armada.account.model.dto;

import com.armada.account.model.enums.AccountBatchOperation;
import com.armada.account.model.enums.AccountBatchScope;
import java.util.List;

/**
 * 账号批量操作预估请求。
 *
 * <p>范围必须显式选择 IDS 或 QUERY，禁止把空 ID 隐式解释为全量账号。</p>
 *
 * @param operation 操作类型
 * @param scope     操作范围
 * @param ids       IDS 范围的账号 ID
 * @param query     QUERY 范围的已生效筛选条件
 */
public record AccountBatchPreviewDTO(
        AccountBatchOperation operation,
        AccountBatchScope scope,
        List<Long> ids,
        AccountBatchQueryDTO query
) {
}
