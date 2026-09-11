package com.armada.contact.task.service;

import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactTaskStatsMapper;
import com.armada.contact.task.model.dto.ContactTaskAccountStatsQuery;
import com.armada.contact.task.model.entity.ContactFriendTask;
import com.armada.contact.task.model.entity.ContactFriendTaskAccount;
import com.armada.contact.task.model.vo.ContactTaskAccountItemVO;
import com.armada.contact.task.model.vo.ContactTaskAccountSummaryVO;
import com.armada.contact.task.model.vo.ContactTaskMetricsVO;
import com.armada.contact.task.model.vo.ContactTaskStatsVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 同一租户读快照上的任务、账号和回执统计，不写入计数副本。 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ContactTaskStatsService {
    private final ContactFriendTaskMapper taskMapper;
    private final ContactFriendTaskAccountMapper accountMapper;
    private final ContactTaskStatsMapper statsMapper;

    /** 复用任务可见性和账号快照，仅增加统计读模型。 */
    public ContactTaskStatsService(ContactFriendTaskMapper taskMapper,
            ContactFriendTaskAccountMapper accountMapper, ContactTaskStatsMapper statsMapper) {
        this.taskMapper = taskMapper;
        this.accountMapper = accountMapper;
        this.statsMapper = statsMapper;
    }

    /** 查询当前租户可见任务的完整统计及异常原因。 */
    public ContactTaskStatsVO stats(Long taskId) {
        ContactFriendTask task = requireTask(taskId);
        ContactTaskStatsVO summary = forTasks(List.of(task)).get(taskId);
        return new ContactTaskStatsVO(taskId, task.getRunStatus(), summary.metrics(),
                summary.accounts(), statsMapper.selectReasons(taskId));
    }

    /** 对已经按租户分页查询的任务批量聚合，空页不发 SQL；列表调用者保持同一事务快照。 */
    public Map<Long, ContactTaskStatsVO> forTasks(List<ContactFriendTask> tasks) {
        if (tasks.isEmpty()) { return Map.of(); }
        List<Long> ids = tasks.stream().map(ContactFriendTask::getId).toList();
        Map<Long, ContactTaskMetricsVO> metrics = metricMap(statsMapper.selectTaskMetrics(ids));
        Map<Long, ContactTaskAccountSummaryVO> accounts = statsMapper.selectAccountSummaries(ids).stream()
                .collect(Collectors.toMap(ContactTaskAccountSummaryVO::taskId, Function.identity()));
        Map<Long, ContactTaskStatsVO> result = new LinkedHashMap<>();
        for (ContactFriendTask task : tasks) {
            Long id = task.getId();
            result.put(id, new ContactTaskStatsVO(id, task.getRunStatus(),
                    metrics.getOrDefault(id, ContactTaskMetricsVO.empty(id)),
                    accounts.getOrDefault(id, ContactTaskAccountSummaryVO.empty(id)), List.of()));
        }
        return result;
    }

    /** 从整个任务账号集合按指标排序后分页，再批量补齐同一快照的指标。 */
    public PageResult<ContactTaskAccountItemVO> accounts(Long taskId, String sortBy,
            String sortOrder, Integer page, Integer pageSize) {
        requireTask(taskId);
        int current = page == null ? 1 : Math.max(1, page);
        int size = pageSize == null || pageSize < 1 ? 20 : Math.min(200, pageSize);
        long total = accountMapper.countByTaskId(taskId);
        var query = new ContactTaskAccountStatsQuery(taskId, sortBy, sortOrder, ((long) current - 1) * size, size);
        List<ContactFriendTaskAccount> accounts = statsMapper.selectAccountPage(query);
        Map<Long, ContactTaskMetricsVO> metrics = accounts.isEmpty() ? Map.of() : metricMap(
                statsMapper.selectAccountMetrics(taskId, accounts.stream().map(ContactFriendTaskAccount::getId).toList()));
        List<ContactTaskAccountItemVO> rows = accounts.stream().map(row -> new ContactTaskAccountItemVO(
                row.getAccountId(), row.getAccountPhoneSnapshot(), row.getAccountStatusSnapshot(),
                row.getNeedSendNum(), row.getSentNum(), row.getFailNum(), row.getId(), row.getState(),
                row.getStopReason(), metrics.getOrDefault(row.getId(), ContactTaskMetricsVO.empty(row.getId())))).toList();
        return PageResult.of(rows, current, size, total);
    }

    private ContactFriendTask requireTask(Long taskId) {
        ContactFriendTask task = taskId == null ? null : taskMapper.selectById(taskId);
        if (task == null) { throw new BusinessException(ErrorCode.NOT_FOUND, "通讯录任务不存在"); }
        return task;
    }

    private static Map<Long, ContactTaskMetricsVO> metricMap(List<ContactTaskMetricsVO> rows) {
        return rows.stream().collect(Collectors.toMap(ContactTaskMetricsVO::scopeId, Function.identity()));
    }
}
