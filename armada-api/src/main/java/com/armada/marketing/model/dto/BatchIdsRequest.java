package com.armada.marketing.model.dto;

import java.util.List;

/**
 * 批量操作请求(按 ID 列表),用于批量删除等。
 */
public record BatchIdsRequest(

        /** 要操作的对象 ID 列表。 */
        List<Long> ids) {
}
