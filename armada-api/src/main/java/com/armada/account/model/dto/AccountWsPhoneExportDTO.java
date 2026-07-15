package com.armada.account.model.dto;

import java.util.List;

/** WS 号码批量导出请求。 */
public record AccountWsPhoneExportDTO(List<Long> ids, String groupName) {
}
