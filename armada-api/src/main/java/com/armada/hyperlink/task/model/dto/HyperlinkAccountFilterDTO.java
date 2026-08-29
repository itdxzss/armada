package com.armada.hyperlink.task.model.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.util.List;

/** 账号筛选白名单快照 v1。 */
public record HyperlinkAccountFilterDTO(
        Integer filterSchemaVersion,
        List<String> countryIso2s,
        List<String> excludeCountryIso2s,
        String continent,
        List<Long> groupIds,
        List<Long> channelIds,
        String protocolId,
        String onlineStatus,
        Integer rotationStatus,
        Integer accountType,
        String platform,
        String widType,
        String importMode,
        Boolean groupInviteAllowed,
        String phone,
        Long importBatchId,
        Integer source,
        Integer friendCountMin,
        Integer friendCountMax,
        BigDecimal retentionDaysMin,
        BigDecimal retentionDaysMax,
        Integer registerDaysMin,
        Integer registerDaysMax,
        Long createdAtFrom,
        Long createdAtTo) {

    /** Spring 默认忽略未知键；账号筛选快照必须在类型局部改为 fail-closed。 */
    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignoredValue) {
        throw new UnknownFieldException(field);
    }

    /** 供超链任务局部 JSON 异常处理识别，避免修改全局 Jackson 容错口径。 */
    public static final class UnknownFieldException extends RuntimeException {
        private final String field;

        public UnknownFieldException(String field) {
            super("accountFilter 未知字段: " + field);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }
}
