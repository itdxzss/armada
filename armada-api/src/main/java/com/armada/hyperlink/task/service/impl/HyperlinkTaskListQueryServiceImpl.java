package com.armada.hyperlink.task.service.impl;

import com.armada.hyperlink.task.converter.HyperlinkTaskListConverter;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskListQuery;
import com.armada.hyperlink.task.model.enums.HyperlinkTaskMode;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskListExportFile;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskListItemVO;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskListRow;
import com.armada.hyperlink.task.service.HyperlinkTaskListQueryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import com.armada.shared.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** task/content/runtime 一次联表分页；禁止触碰 recipient 与其他统计表。 */
@Service
public class HyperlinkTaskListQueryServiceImpl implements HyperlinkTaskListQueryService {

    private static final int TASK_NAME_MAX_LENGTH = 128;
    private static final String UNKNOWN_COUNTRY = "UNKNOWN";
    private static final Pattern ISO2_PATTERN = Pattern.compile("^[A-Z]{2}$");
    private static final String CSV_CONTENT_TYPE = "text/csv;charset=UTF-8";
    private static final String[] CSV_HEADERS = {
        "ID", "任务名称", "推广链接", "消息类型", "营销目标国家", "数据包", "数据包号码数", "账号范围", "状态",
        "双钩数", "双钩率", "点击 UV 数", "点击率", "单钩数", "失败数", "未开通 WS", "受众总数", "使用号数",
        "封号数", "号均发量", "最大执行账号数", "已执行时长", "任务模式", "计划结束时间", "周期间隔", "创建时间"
    };
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HyperlinkTaskMapper mapper;
    private final HyperlinkTaskListConverter converter;
    private final LongSupplier nowSupplier;

    @Autowired
    public HyperlinkTaskListQueryServiceImpl(
            HyperlinkTaskMapper mapper, HyperlinkTaskListConverter converter) {
        this(mapper, converter, System::currentTimeMillis);
    }

    HyperlinkTaskListQueryServiceImpl(
            HyperlinkTaskMapper mapper, HyperlinkTaskListConverter converter, LongSupplier nowSupplier) {
        this.mapper = mapper;
        this.converter = converter;
        this.nowSupplier = nowSupplier;
    }

    @Override
    public PageResult<HyperlinkTaskListItemVO> list(HyperlinkTaskListQuery query) {
        HyperlinkTaskListQuery normalized = normalize(query);
        long total = mapper.countList(normalized);
        if (total == 0) {
            return PageResult.of(List.of(), normalized.getPage(), normalized.getPageSize(), 0);
        }
        long now = nowSupplier.getAsLong();
        List<HyperlinkTaskListItemVO> items = mapper.selectList(normalized).stream()
                .map(row -> converter.toItem(row, now))
                .toList();
        return PageResult.of(items, normalized.getPage(), normalized.getPageSize(), total);
    }

    @Override
    public HyperlinkTaskListExportFile export(HyperlinkTaskListQuery query) {
        HyperlinkTaskListQuery normalized = normalize(query);
        long now = nowSupplier.getAsLong();
        List<HyperlinkTaskListItemVO> items = mapper.selectExport(normalized).stream()
                .map(row -> converter.toItem(row, now))
                .toList();
        StringBuilder csv = new StringBuilder("\uFEFF");
        appendCsvRow(csv, List.of(CSV_HEADERS));
        for (HyperlinkTaskListItemVO item : items) {
            appendCsvRow(csv, exportValues(item));
        }
        String timestamp = FILE_TIME.format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()));
        return new HyperlinkTaskListExportFile(
                "hyperlink-tasks-" + timestamp + ".csv", CSV_CONTENT_TYPE,
                csv.toString().getBytes(StandardCharsets.UTF_8), items.size());
    }

    private HyperlinkTaskListQuery normalize(HyperlinkTaskListQuery query) {
        HyperlinkTaskListQuery value = query == null ? new HyperlinkTaskListQuery() : query;
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        value.setTenantId(tenantId);
        String taskName = trim(value.getTaskName());
        if (taskName != null && taskName.length() > TASK_NAME_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务名称最多 128 个字符");
        }
        value.setTaskName(taskName);
        value.setTaskNameLike(taskName == null ? null : escapeLike(taskName));
        if (value.getRunStatus() != null
                && (value.getRunStatus() < 0 || value.getRunStatus() > 4)) {
            throw new BusinessException(ErrorCode.VALIDATION, "runStatus 非法");
        }
        String taskMode = trim(value.getTaskMode());
        value.setTaskMode(taskMode);
        value.setTaskModeCode(taskMode == null ? null : HyperlinkTaskMode.fromApi(taskMode).code());
        String country = trim(value.getCountryIso2());
        country = country == null ? null : country.toUpperCase(Locale.ROOT);
        if (country != null && !UNKNOWN_COUNTRY.equals(country) && !ISO2_PATTERN.matcher(country).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION, "countryIso2 非法");
        }
        value.setCountryIso2(country);
        value.setUnknownCountry(UNKNOWN_COUNTRY.equals(country));
        if (value.getCreatedAtStart() != null && value.getCreatedAtStart() < 0
                || value.getCreatedAtEnd() != null && value.getCreatedAtEnd() < 0
                || value.getCreatedAtStart() != null && value.getCreatedAtEnd() != null
                    && value.getCreatedAtStart() >= value.getCreatedAtEnd()) {
            throw new BusinessException(ErrorCode.VALIDATION, "创建时间范围非法");
        }
        return value;
    }

    private List<String> exportValues(HyperlinkTaskListItemVO item) {
        String countries = item.targetCountryIso2s().isEmpty() ? "-"
                : String.join("/", item.targetCountryIso2s().stream()
                        .map(value -> value == null ? "未知" : value).toList());
        List<String> filterLabels = converter.accountFilterLabels(item.accountFilter());
        String filters = filterLabels.isEmpty() ? "未限制" : String.join("；", filterLabels);
        String clickUv = item.shortLinkEnabled() ? String.valueOf(item.clickUvNum()) : "-";
        String clickRate = item.shortLinkEnabled() ? percent(item.clickUvNum(), item.successNum()) : "-";
        return List.of(
                String.valueOf(item.id()), item.taskName(), nullable(item.promotionLink()),
                messageType(item.messageType()), countries, nullable(item.dataPackageName()),
                String.valueOf(item.recipientTotal()), filters, status(item),
                String.valueOf(item.deliveredNum()), percent(item.deliveredNum(), item.successNum()),
                clickUv, clickRate, String.valueOf(item.successNum()), String.valueOf(item.failedNum()),
                String.valueOf(item.unregisteredNum()), String.valueOf(item.recipientTotal()),
                String.valueOf(item.usedAccountCount()), String.valueOf(item.invalidAccountCount()),
                decimal(item.successNum(), item.usedAccountCount()), String.valueOf(item.actualConcurrency()),
                String.valueOf(item.executionDurationSec()), taskMode(item.taskMode()),
                time(item.plannedEndAt()), interval(item.cycleIntervalMinutes()), time(item.createdAt()));
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static void appendCsvRow(StringBuilder csv, List<String> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                csv.append(',');
            }
            String value = values.get(index) == null ? "" : values.get(index);
            csv.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        csv.append("\r\n");
    }

    private static String messageType(int type) {
        return switch (type) {
            case 1 -> "单图文";
            case 2 -> "双图文";
            case 3 -> "普通按钮";
            case 4 -> "卡片按钮";
            default -> "未知(" + type + ")";
        };
    }

    private static String status(HyperlinkTaskListItemVO item) {
        if (!item.enabled()) {
            return "已停用";
        }
        return switch (item.runStatus()) {
            case 0 -> "未开始";
            case 1 -> "进行中";
            case 2 -> "已完成";
            case 3 -> "已暂停";
            case 4 -> "已停止";
            default -> "未知";
        };
    }

    private static String taskMode(String mode) {
        return switch (mode) {
            case "instant" -> "即时";
            case "rolling" -> "预发布";
            case "cycle" -> "周期";
            default -> mode;
        };
    }

    private static String percent(long numerator, long denominator) {
        if (denominator == 0) {
            return "0.00%";
        }
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP) + "%";
    }

    private static String decimal(long numerator, long denominator) {
        if (denominator == 0) {
            return "0.0";
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String time(Long epochMillis) {
        if (epochMillis == null) {
            return "-";
        }
        return DISPLAY_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    private static String interval(int minutes) {
        return minutes <= 0 ? "-" : minutes + " 分钟";
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String trim(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
