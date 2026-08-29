package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.mapper.HyperlinkTaskAccountStatMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskMapper;
import com.armada.hyperlink.task.mapper.HyperlinkTaskRecipientMapper;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountStatQuery;
import com.armada.hyperlink.task.model.query.HyperlinkAccountStatCriteria;
import com.armada.hyperlink.task.model.vo.HyperlinkAccountStatItemVO;
import com.armada.hyperlink.task.model.vo.HyperlinkAccountStatRow;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 发信账号维度统计查询；选择累计投影或时间范围事实 SQL。 */
@Service
public class HyperlinkAccountStatQueryService {

    private static final BigDecimal MILLIS_PER_DAY = new BigDecimal("86400000");

    private final HyperlinkTaskMapper taskMapper;
    private final HyperlinkTaskAccountStatMapper accountStatMapper;
    private final HyperlinkTaskRecipientMapper recipientMapper;
    private final HyperlinkAccountStatCriteriaFactory criteriaFactory;
    private final Clock clock;

    @Autowired
    public HyperlinkAccountStatQueryService(HyperlinkTaskMapper taskMapper,
            HyperlinkTaskAccountStatMapper accountStatMapper,
            HyperlinkTaskRecipientMapper recipientMapper,
            HyperlinkAccountStatCriteriaFactory criteriaFactory) {
        this(taskMapper, accountStatMapper, recipientMapper, criteriaFactory, Clock.systemUTC());
    }

    HyperlinkAccountStatQueryService(HyperlinkTaskMapper taskMapper,
            HyperlinkTaskAccountStatMapper accountStatMapper,
            HyperlinkTaskRecipientMapper recipientMapper,
            HyperlinkAccountStatCriteriaFactory criteriaFactory, Clock clock) {
        this.taskMapper = taskMapper;
        this.accountStatMapper = accountStatMapper;
        this.recipientMapper = recipientMapper;
        this.criteriaFactory = criteriaFactory;
        this.clock = clock;
    }

    public PageResult<HyperlinkAccountStatItemVO> list(long taskId, HyperlinkAccountStatQuery query) {
        requireTask(taskId);
        HyperlinkAccountStatCriteria criteria = criteriaFactory.page(taskId, query, clock.millis());
        long total = criteria.timeScoped()
                ? recipientMapper.countAccountStats(criteria)
                : accountStatMapper.countAccountStats(criteria);
        List<HyperlinkAccountStatRow> rows = total == 0 ? List.of()
                : criteria.timeScoped()
                        ? recipientMapper.selectAccountStats(criteria)
                        : accountStatMapper.selectAccountStats(criteria);
        List<HyperlinkAccountStatItemVO> items = rows.stream()
                .map(row -> toItem(row, criteria.snapshotAt()))
                .toList();
        int page = query == null ? 1 : query.getPage();
        int pageSize = criteria.pageSize();
        return PageResult.of(items, page, pageSize, total);
    }

    /** 按已规范化条件读取一个稳定批次，供异步 CSV writer 使用。 */
    public List<HyperlinkAccountStatRow> loadBatch(HyperlinkAccountStatCriteria criteria) {
        return criteria.timeScoped()
                ? recipientMapper.selectAccountStats(criteria)
                : accountStatMapper.selectAccountStats(criteria);
    }

    /** 导出服务复用相同的行映射，避免页面与 CSV 派生字段口径漂移。 */
    public HyperlinkAccountStatItemVO toItem(HyperlinkAccountStatRow row, long snapshotAt) {
        boolean unassigned = row.getAccountId() == null;
        return new HyperlinkAccountStatItemVO(
                unassigned ? 0L : zero(row.getBucketKey()),
                row.getAccountId(),
                unassigned ? null : row.getSenderPhone(),
                unassigned ? null : row.getSenderCountryIso2(),
                unassigned ? null : accountType(row.getAccountTypeCode()),
                unassigned ? BigDecimal.ZERO.setScale(1) : retention(snapshotAt, row.getAccountCreatedAt()),
                zero(row.getSuccessNum()),
                zero(row.getDeliveredNum()),
                zero(row.getFailedNum()),
                row.getLastSendAt());
    }

    public void requireTask(long taskId) {
        if (taskId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务 ID 无效");
        }
        if (taskMapper.selectById(taskId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "超链任务不存在");
        }
    }

    private static BigDecimal retention(long snapshotAt, Long accountCreatedAt) {
        if (accountCreatedAt == null) {
            return BigDecimal.ZERO.setScale(1);
        }
        long ageMillis = Math.max(0, snapshotAt - accountCreatedAt);
        return BigDecimal.valueOf(ageMillis)
                .divide(MILLIS_PER_DAY, 1, RoundingMode.HALF_UP);
    }

    private static String accountType(Integer code) {
        if (Integer.valueOf(1).equals(code)) {
            return "PERSONAL";
        }
        if (Integer.valueOf(2).equals(code)) {
            return "BUSINESS";
        }
        return null;
    }

    private static long zero(Long value) {
        return value == null ? 0 : Math.max(0, value);
    }
}
