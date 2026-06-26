package com.armada.task.service;

import com.armada.task.model.dto.SelectedAccount;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** id 列表 ↔ JSON 串互转(用于 account_group_ids/selected_account_ids 快照列)。 */
public final class JsonIds {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonIds() {
    }

    /** id 列表转 JSON 数组串;null/空 → "[]"。 */
    public static String toJson(List<?> ids) {
        if (ids == null || ids.isEmpty()) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    /** JSON 数组串转 Long 列表;null/空/解析失败 → 空列表。 */
    public static List<Long> parseLongs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Long>>() {
            });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /** 从选中账号取非空 accountId 列表。 */
    public static List<Long> idsOf(List<SelectedAccount> accs) {
        if (accs == null) {
            return List.of();
        }
        return accs.stream().map(SelectedAccount::accountId).filter(Objects::nonNull).collect(Collectors.toList());
    }
}
