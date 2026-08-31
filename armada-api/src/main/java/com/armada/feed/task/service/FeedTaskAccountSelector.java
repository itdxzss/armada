package com.armada.feed.task.service;

import com.armada.account.selection.model.SelectedAccount;
import com.armada.feed.task.mapper.FeedTaskAccountCandidateMapper;
import com.armada.feed.task.model.dto.FeedTaskCandidateQuery;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 动态发布任务账号筛选解析与候选查询。 */
@Component
public class FeedTaskAccountSelector {

    private static final int ACCOUNT_STATE_NORMAL = 2;
    private static final int LOGIN_STATE_ONLINE = 1;

    private final FeedTaskAccountCandidateMapper candidateMapper;
    private final ObjectMapper objectMapper;

    public FeedTaskAccountSelector(FeedTaskAccountCandidateMapper candidateMapper, ObjectMapper objectMapper) {
        this.candidateMapper = candidateMapper;
        this.objectMapper = objectMapper;
    }

    /** 按任务筛选条件查询尚未加入该任务的可发布账号。 */
    public List<SelectedAccount> selectCandidates(String filterJson, Long taskId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return candidateMapper.selectCandidates(candidateQuery(filterJson, taskId, limit));
    }

    /** 将前端筛选 JSON 规整后入库。 */
    public String normalizeToJson(String filterJson) {
        String json = filterJson == null || filterJson.isBlank() ? "{}" : filterJson;
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {
            });
            return objectMapper.writeValueAsString(compact(parsed));
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号筛选条件无法解析");
        }
    }

    /** 把已存筛选 JSON 还原为前端对象。 */
    public Map<String, Object> toViewFilter(String filterJson) {
        try {
            return objectMapper.readValue(normalizeToJson(filterJson), new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    private FeedTaskCandidateQuery candidateQuery(String filterJson, Long taskId, int limit) {
        JsonNode root = readTree(filterJson);
        FeedTaskCandidateQuery query = new FeedTaskCandidateQuery();
        query.setTaskId(taskId);
        query.setLimit(limit);
        query.setKeyword(text(root, "keyword"));
        query.setPhone(text(root, "phone"));
        query.setAccountType(integer(root, "accountType"));
        query.setProtocolId(text(root, "protocolId"));
        query.setNumberSource(integer(root, "numberSource"));
        query.setChannelName(text(root, "channelName"));
        query.setAccountGroupId(longValue(root, "accountGroupId"));
        query.setAccountState(integer(root, "accountState") == null
                ? ACCOUNT_STATE_NORMAL : integer(root, "accountState"));
        query.setLoginState(integer(root, "loginState") == null
                ? LOGIN_STATE_ONLINE : integer(root, "loginState"));
        query.setRiskStatus(integer(root, "riskStatus"));
        query.setMuteStatus(muteStatus(root));
        query.setCountry(text(root, "country"));
        query.setTruthIp(text(root, "truthIp"));
        query.setCallable(booleanValue(root, "callable"));
        return query;
    }

    private JsonNode readTree(String filterJson) {
        try {
            return objectMapper.readTree(normalizeToJson(filterJson));
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号筛选条件无法解析");
        }
    }

    private static Map<String, Object> compact(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof String text && text.isBlank()) {
                continue;
            }
            if (value instanceof List<?> list && list.isEmpty()) {
                continue;
            }
            result.put(entry.getKey(), value);
        }
        return result;
    }

    private static String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static Integer integer(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.isNumber() ? value.asInt() : Integer.valueOf(value.asText());
    }

    private static Long longValue(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.isNumber() ? value.asLong() : Long.valueOf(value.asText());
    }

    private static Boolean booleanValue(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.isBoolean() ? value.asBoolean() : Boolean.valueOf(value.asText());
    }

    private static Integer muteStatus(JsonNode root) {
        String value = text(root, "muteStatus");
        if (value == null) {
            return null;
        }
        if ("6h".equalsIgnoreCase(value)) {
            return 1;
        }
        if ("24h".equalsIgnoreCase(value)) {
            return 2;
        }
        return Integer.valueOf(value);
    }
}
