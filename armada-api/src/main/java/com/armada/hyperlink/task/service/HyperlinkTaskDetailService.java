package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskDetailMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkRecipientQuery;
import com.armada.hyperlink.task.model.enums.HyperlinkRecipientStatus;
import com.armada.hyperlink.task.model.vo.HyperlinkRecipientItemVO;
import com.armada.hyperlink.task.model.vo.HyperlinkRecipientRow;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskSummaryRow;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskSummaryVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 详情抽屉公共摘要和 H4 收信人流水查询。 */
@Service
public class HyperlinkTaskDetailService {

    private static final Set<Integer> PAGE_SIZES = Set.of(10, 20, 50, 100, 200);
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9]+$");

    private final HyperlinkTaskMapper taskMapper;
    private final HyperlinkTaskDetailMapper detailMapper;
    private final Clock clock;

    @Autowired
    public HyperlinkTaskDetailService(
            HyperlinkTaskMapper taskMapper,
            HyperlinkTaskDetailMapper detailMapper) {
        this(taskMapper, detailMapper, Clock.systemUTC());
    }

    HyperlinkTaskDetailService(
            HyperlinkTaskMapper taskMapper,
            HyperlinkTaskDetailMapper detailMapper,
            Clock clock) {
        this.taskMapper = taskMapper;
        this.detailMapper = detailMapper;
        this.clock = clock;
    }

    public HyperlinkTaskSummaryVO summary(long taskId) {
        requireTask(taskId);
        HyperlinkTaskSummaryRow row = detailMapper.selectSummary(taskId);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务运行数据不存在");
        }
        long duration = nonNegative(row.getExecutionDurationSec());
        if (Integer.valueOf(1).equals(row.getRunStatus()) && row.getActiveSinceAt() != null) {
            duration += Math.max(0, (clock.millis() - row.getActiveSinceAt()) / 1000);
        }
        return new HyperlinkTaskSummaryVO(
                row.getId(), row.getTaskName(), nonNegative(row.getRecipientTotal()),
                nonNegative(row.getSendTotal()), nonNegative(row.getSuccessNum()),
                nonNegative(row.getDeliveredNum()), nonNegative(row.getReadNum()),
                nonNegative(row.getFailedNum()), nonNegative(row.getUnregisteredNum()),
                nonNegative(row.getUsedAccountCount()), nonNegative(row.getInvalidAccountCount()),
                nonNegative(row.getClickUvNum()), nonNegative(row.getClickTotal()),
                nonNegative(row.getActualConcurrency()), duration, row.getMetricsUpdatedAt(),
                row.getFirstVisitAt(), row.getLastVisitAt());
    }

    public PageResult<HyperlinkRecipientItemVO> recipients(
            long taskId, HyperlinkRecipientQuery source) {
        requireTask(taskId);
        HyperlinkRecipientQuery query = normalize(taskId, source, true);
        long total = detailMapper.countRecipients(query);
        List<HyperlinkRecipientItemVO> rows = total == 0
                ? List.of()
                : detailMapper.selectRecipients(query).stream().map(this::toItem).toList();
        return PageResult.of(rows, query.getPage(), query.getPageSize(), total);
    }

    /** 规范化页面和导出共用的四项筛选及唯一排序白名单。 */
    public HyperlinkRecipientQuery normalize(
            long taskId, HyperlinkRecipientQuery source, boolean validatePage) {
        if (taskId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务 ID 无效");
        }
        HyperlinkRecipientQuery query = source == null ? new HyperlinkRecipientQuery() : source;
        query.setTaskId(taskId);
        if (validatePage && !PAGE_SIZES.contains(query.getPageSize())) {
            throw new BusinessException(ErrorCode.VALIDATION, "pageSize 仅支持 10/20/50/100/200");
        }
        String phone = trimToNull(query.getPhone());
        if (phone != null && (phone.length() > 32 || !PHONE.matcher(phone).matches())) {
            throw new BusinessException(ErrorCode.VALIDATION, "收信号码仅支持数字和可选前导 +，最多 32 位");
        }
        query.setPhone(phone);
        query.setPhoneLike(phone == null ? null : '%' + escapeLike(phone) + '%');
        query.setRecipientCountryIso2(normalizeCountry(query.getRecipientCountryIso2()));
        query.setSenderCountryIso2(normalizeCountry(query.getSenderCountryIso2()));
        String failReason = trimToNull(query.getFailReason());
        if (failReason != null && failReason.length() > 255) {
            throw new BusinessException(ErrorCode.VALIDATION, "失败原因最多 255 个字符");
        }
        query.setFailReason(failReason);
        String sortField = trimToNull(query.getSortField());
        if (sortField == null) {
            sortField = "id";
        }
        if (!"id".equals(sortField)) {
            throw new BusinessException(ErrorCode.VALIDATION, "不支持的排序字段");
        }
        query.setSortField(sortField);
        String sortOrder = trimToNull(query.getSortOrder());
        sortOrder = sortOrder == null ? "asc" : sortOrder.toLowerCase(Locale.ROOT);
        if (!"asc".equals(sortOrder) && !"desc".equals(sortOrder)) {
            throw new BusinessException(ErrorCode.VALIDATION, "排序方向仅支持 asc/desc");
        }
        query.setSortOrder(sortOrder);
        return query;
    }

    public HyperlinkRecipientItemVO toItem(HyperlinkRecipientRow row) {
        HyperlinkRecipientStatus status = HyperlinkRecipientStatus.fromCode(row.getStatusCode());
        return new HyperlinkRecipientItemVO(
                row.getId(), row.getRecipientPhone(), row.getRecipientCountryIso2(),
                row.getAccountId(), row.getSenderPhone(), row.getSenderCountryIso2(), status,
                row.getFailCode(), row.getFailReason(), row.getStatusAt());
    }

    private void requireTask(long taskId) {
        if (taskId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务 ID 无效");
        }
        if (taskMapper.selectById(taskId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "超链任务不存在");
        }
    }

    private static String normalizeCountry(String source) {
        String country = trimToNull(source);
        if (country == null) {
            return null;
        }
        country = country.toUpperCase(Locale.ROOT);
        if ("UNKNOWN".equals(country)) {
            return country;
        }
        if (country.length() != 2
                || country.charAt(0) < 'A' || country.charAt(0) > 'Z'
                || country.charAt(1) < 'A' || country.charAt(1) > 'Z') {
            throw new BusinessException(ErrorCode.VALIDATION, "国家或地区编码必须为 2 位 ISO2");
        }
        return country;
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static long nonNegative(Long value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static int nonNegative(Integer value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
