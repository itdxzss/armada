package com.armada.feed.task.model.dto;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.paging.PageQuery;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 动态发布任务分页查询。 */
public class FeedTaskQuery extends PageQuery {

    private String name;
    private Integer taskStatus;
    private String createdAtStart;
    private String createdAtEnd;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null || name.isBlank() ? null : name.trim();
    }

    public Integer getTaskStatus() {
        return taskStatus;
    }

    public void setTaskStatus(Integer taskStatus) {
        this.taskStatus = taskStatus;
    }

    public String getCreatedAtStart() {
        return createdAtStart;
    }

    public void setCreatedAtStart(String createdAtStart) {
        this.createdAtStart = createdAtStart;
    }

    public String getCreatedAtEnd() {
        return createdAtEnd;
    }

    public void setCreatedAtEnd(String createdAtEnd) {
        this.createdAtEnd = createdAtEnd;
    }

    public Long getCreatedAtStartMillis() {
        return parseTime(createdAtStart, "createdAtStart");
    }

    public Long getCreatedAtEndMillis() {
        return parseTime(createdAtEnd, "createdAtEnd");
    }

    public static Long parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            if (trimmed.matches("\\d+")) {
                return Long.parseLong(trimmed);
            }
            if (trimmed.endsWith("Z") || trimmed.contains("+")) {
                return Instant.parse(trimmed).toEpochMilli();
            }
            return LocalDateTime.parse(trimmed).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.VALIDATION, field + " 时间格式非法");
        }
    }
}
