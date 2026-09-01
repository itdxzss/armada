package com.armada.hyperlink.task.converter;

import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import com.armada.hyperlink.task.model.enums.HyperlinkProvisionStatus;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskListItemVO;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskListRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Component;

/** 把三表查询投影转换为公共列表合同，并生成完整账号筛选标签。 */
@Component
public class HyperlinkTaskListConverter {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final ObjectMapper objectMapper;

    public HyperlinkTaskListConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 转换列表项；运行中时长包含当前连续运行段。 */
    public HyperlinkTaskListItemVO toItem(HyperlinkTaskListRow row, long now) {
        return new HyperlinkTaskListItemVO(
                number(row.getId()), text(row.getTaskName()), number(row.getMessageType()),
                taskMode(row.getTaskType()), Boolean.TRUE.equals(row.getEnabled()),
                number(row.getRunStatus()),
                HyperlinkProvisionStatus.fromCode(row.getProvisionStatus()),
                Boolean.TRUE.equals(row.getShortLinkEnabled()),
                number(row.getVersion()), row.getPromotionLink(), row.getDataPackageId(),
                row.getDataPackageName(), accountFilter(row.getAccountFilterJson()),
                countries(row.getTargetCountryIso2sJson()), row.getPlannedEndAt(),
                number(row.getCycleIntervalMinutes()), number(row.getCreatedAt()),
                number(row.getRecipientTotal()), number(row.getSendTotal()),
                number(row.getSuccessNum()), number(row.getDeliveredNum()), number(row.getReadNum()),
                number(row.getFailedNum()), number(row.getUnregisteredNum()),
                number(row.getUsedAccountCount()), number(row.getInvalidAccountCount()),
                number(row.getClickUvNum()), number(row.getClickTotal()),
                number(row.getActualConcurrency()), executionDuration(row, now),
                row.getMetricsUpdatedAt());
    }

    /** 账号筛选完整标签；列表可自行截断，导出始终使用全量。 */
    public List<String> accountFilterLabels(HyperlinkAccountFilterDTO filter) {
        if (filter == null) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        addList(labels, "包含国家", filter.countryIso2s());
        addList(labels, "排除国家", filter.excludeCountryIso2s());
        add(labels, "大洲", filter.continent());
        addList(labels, "业务组", filter.groupIds());
        addList(labels, "渠道", filter.channelIds());
        add(labels, "协议", filter.protocolId());
        add(labels, "在线状态", filter.onlineStatus());
        add(labels, "轮转状态", filter.rotationStatus());
        add(labels, "账号类型", filter.accountType());
        add(labels, "平台", filter.platform());
        add(labels, "设备类型", filter.widType());
        add(labels, "导入方式", filter.importMode());
        add(labels, "允许拉群", filter.groupInviteAllowed());
        add(labels, "手机号", filter.phone());
        add(labels, "导入批次", filter.importBatchId());
        add(labels, "来源", filter.source());
        addRange(labels, "好友数", filter.friendCountMin(), filter.friendCountMax());
        addRange(labels, "存活天数", filter.retentionDaysMin(), filter.retentionDaysMax());
        addRange(labels, "注册天数", filter.registerDaysMin(), filter.registerDaysMax());
        addRange(labels, "入库时间", filter.createdAtFrom(), filter.createdAtTo());
        return List.copyOf(labels);
    }

    private HyperlinkAccountFilterDTO accountFilter(String json) {
        try {
            return objectMapper.readValue(json == null || json.isBlank() ? "{}" : json,
                    HyperlinkAccountFilterDTO.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("超链任务账号筛选快照无法解析", exception);
        }
    }

    private List<String> countries(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(json, STRING_LIST);
            LinkedHashSet<String> normalized = new LinkedHashSet<>();
            if (values != null) {
                for (String value : values) {
                    normalized.add(value == null ? null : value.trim().toUpperCase());
                }
            }
            return Collections.unmodifiableList(new ArrayList<>(normalized));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("超链任务国家快照无法解析", exception);
        }
    }

    private static long executionDuration(HyperlinkTaskListRow row, long now) {
        long duration = number(row.getExecutionDurationSec());
        if (number(row.getRunStatus()) == 1 && row.getActiveSinceAt() != null) {
            duration += Math.max(0L, now - row.getActiveSinceAt()) / 1000L;
        }
        return duration;
    }

    private static String taskMode(Integer code) {
        return switch (number(code)) {
            case 1 -> "instant";
            case 2 -> "rolling";
            case 3 -> "cycle";
            default -> throw new IllegalStateException("未知超链任务模式: " + code);
        };
    }

    private static void add(List<String> labels, String name, Object value) {
        if (value != null && !value.toString().isBlank()) {
            labels.add(name + ":" + value);
        }
    }

    private static void addList(List<String> labels, String name, List<?> values) {
        if (values != null && !values.isEmpty()) {
            labels.add(name + ":" + String.join("/", values.stream().map(String::valueOf).toList()));
        }
    }

    private static void addRange(List<String> labels, String name, Object start, Object end) {
        if (start != null || end != null) {
            labels.add(name + ":" + (start == null ? "不限" : start)
                    + "~" + (end == null ? "不限" : end));
        }
    }

    private static String text(String value) { return value == null ? "" : value; }
    private static int number(Integer value) { return value == null ? 0 : value; }
    private static long number(Long value) { return value == null ? 0L : value; }
}
