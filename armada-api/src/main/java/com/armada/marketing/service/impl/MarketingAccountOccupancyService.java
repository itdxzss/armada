package com.armada.marketing.service.impl;

import com.armada.marketing.mapper.MarketingAccountOccupancyMapper;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.vo.MarketingAccountOccupancyOwnerRow;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 普通群组营销账号当前占用领域服务。
 *
 * <p>占用粒度是账号，不存在分组锁。任务和目标保存后立即在同一事务内锁定全部账号，
 * 数据库唯一键保证同租户同账号只有一个普通营销任务持有。</p>
 */
@Service
public class MarketingAccountOccupancyService {

    private static final Logger log = LoggerFactory.getLogger(MarketingAccountOccupancyService.class);
    private static final String GENERIC_ATTEMPT_OCCUPIED_MESSAGE =
            "账号正在被其它营销任务占用，本轮未发送。";
    private static final String GENERIC_LOCK_FAILED_MESSAGE = "营销账号锁定失败，请刷新后重试";
    private static final String UNKNOWN_OWNER_TASK_NAME = "其它营销任务";
    private static final DateTimeFormatter RELEASE_TIME_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final MarketingAccountOccupancyMapper mapper;

    /**
     * 创建账号占用领域服务。
     *
     * @param mapper 当前占用关系 Mapper
     */
    public MarketingAccountOccupancyService(MarketingAccountOccupancyMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 为非终态任务补齐当前空闲的目标账号占用，并返回所有目标账号的当前占用方。
     *
     * <p>冲突账号不会导致整单失败；调用方按 ownerTaskId 决定本轮发送或记录占用跳过。</p>
     *
     * @param task 普通营销任务，必须已持久化
     * @param now  抢占时间(epoch毫秒)
     * @return accountId 到当前占用方的映射
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<Long, MarketingAccountOccupancyOwnerRow> acquireAndLoadTaskAccounts(MarketingTask task, long now) {
        if (task == null || task.getId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "营销任务不能为空");
        }
        int staleDeleted = mapper.deleteStale(now);
        int acquired = mapper.insertAvailableTaskAccounts(task.getId(), now);
        List<MarketingAccountOccupancyOwnerRow> rows = mapper.selectOwnersByTaskAccounts(task.getId());
        Map<Long, MarketingAccountOccupancyOwnerRow> owners = new LinkedHashMap<>();
        for (MarketingAccountOccupancyOwnerRow row : rows) {
            owners.put(row.getAccountId(), row);
        }
        log.info("营销任务账号抢占完成 tenantId={} taskId={} acquired={} owners={} staleDeleted={}",
                task.getTenantId(), task.getId(), acquired, owners.size(), staleDeleted);
        return Map.copyOf(owners);
    }

    /**
     * 在任务创建事务内锁定全部所选账号，任一账号冲突都会让整个创建事务回滚。
     *
     * <p>前端禁选和创建前查询都可能遇到并发变化，只有占用表唯一键和 owner 复查是最终闸门。
     * owner 数量少于任务去重账号数时同样拒绝，避免部分账号锁定后仍返回创建成功。</p>
     *
     * @param task 已保存主表和目标的普通营销任务
     * @param now  锁定时间(epoch毫秒)
     * @return accountId 到当前 owner 的完整映射
     * @throws BusinessException 任一账号被其它任务持有或锁定结果不完整时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<Long, MarketingAccountOccupancyOwnerRow> lockTaskAccountsOrThrow(MarketingTask task, long now) {
        Map<Long, MarketingAccountOccupancyOwnerRow> owners = acquireAndLoadTaskAccounts(task, now);
        MarketingAccountOccupancyOwnerRow conflict = owners.values().stream()
                .filter(owner -> !Objects.equals(task.getId(), owner.getMarketingTaskId()))
                .min(Comparator.comparing(MarketingAccountOccupancyOwnerRow::getAccountId))
                .orElse(null);
        if (conflict != null) {
            String ownerTaskName = ownerTaskName(conflict);
            log.warn("营销任务创建锁定账号冲突 tenantId={} taskId={} accountId={} ownerTaskId={} ownerTaskName={}",
                    task.getTenantId(), task.getId(), conflict.getAccountId(), conflict.getMarketingTaskId(),
                    ownerTaskName);
            throw new BusinessException(ErrorCode.CONFLICT, selectionOccupiedMessage(conflict));
        }

        int expectedAccountCount = task.getSelectedAccountCount() == null ? 0 : task.getSelectedAccountCount();
        if (owners.size() != expectedAccountCount) {
            log.error("营销任务创建锁定账号数量不完整 tenantId={} taskId={} expected={} actual={}",
                    task.getTenantId(), task.getId(), expectedAccountCount, owners.size());
            throw new BusinessException(ErrorCode.CONFLICT, GENERIC_LOCK_FAILED_MESSAGE);
        }
        log.info("营销任务创建账号锁定完成 tenantId={} taskId={} lockedAccounts={}",
                task.getTenantId(), task.getId(), owners.size());
        return owners;
    }

    /**
     * 批量读取账号当前有效占用方，供账号树在展示阶段禁选已锁定账号。
     *
     * <p>该查询只是用户体验层的前置提示；任务保存仍以占用表唯一键作为最终并发闸门。</p>
     *
     * @param accountIds 账号 ID 列表
     * @return accountId 到当前占用任务的映射
     */
    public Map<Long, MarketingAccountOccupancyOwnerRow> loadActiveOwners(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, MarketingAccountOccupancyOwnerRow> owners = new LinkedHashMap<>();
        for (MarketingAccountOccupancyOwnerRow row : mapper.selectOwnersByAccountIds(accountIds)) {
            owners.put(row.getAccountId(), row);
        }
        return Map.copyOf(owners);
    }

    /**
     * 构造账号选择阶段的占用提示。
     *
     * @param owner 当前占用任务
     * @return 可直接展示给运营的占用提示
     */
    public static String selectionOccupiedMessage(MarketingAccountOccupancyOwnerRow owner) {
        return "该账号正在被任务【" + ownerTaskName(owner) + "】占用，请先关闭原任务后再使用。";
    }

    /**
     * 释放指定普通营销任务持有的全部账号。
     *
     * @param taskId 普通营销任务 ID
     * @return 实际释放账号数
     */
    @Transactional(rollbackFor = Exception.class)
    public int releaseTaskAccounts(Long taskId) {
        int released = mapper.releaseByTaskId(taskId);
        log.info("营销任务账号释放完成 taskId={} released={}", taskId, released);
        return released;
    }

    /**
     * 释放引用指定模板的普通营销任务账号租约。
     *
     * @param templateIds 已删除模板 ID 列表
     * @return 实际释放账号数
     */
    @Transactional(rollbackFor = Exception.class)
    public int releaseAccountsByTemplateIds(List<Long> templateIds) {
        if (templateIds == null || templateIds.isEmpty()) {
            return 0;
        }
        int released = mapper.releaseByTemplateIds(templateIds);
        log.info("营销模板删除释放任务账号 templateCount={} released={}", templateIds.size(), released);
        return released;
    }

    /**
     * 构造单条发送明细的账号占用跳过原因。
     *
     * @param owner 当前占用方；信息不可用时可为空
     * @return 可直接展示在营销任务明细中的中文原因
     */
    public String occupiedAttemptMessage(MarketingAccountOccupancyOwnerRow owner) {
        if (!hasOwnerReleaseDetails(owner)) {
            return GENERIC_ATTEMPT_OCCUPIED_MESSAGE;
        }
        return "账号已被营销任务【" + owner.getTaskName() + "】占用，预计于【"
                + formatReleaseTime(owner.getTaskEndAt())
                + "】释放，本轮未发送。";
    }

    private static boolean hasOwnerReleaseDetails(MarketingAccountOccupancyOwnerRow owner) {
        return owner != null && StringUtils.hasText(owner.getTaskName()) && owner.getTaskEndAt() != null;
    }

    private static String ownerTaskName(MarketingAccountOccupancyOwnerRow owner) {
        return owner != null && StringUtils.hasText(owner.getTaskName())
                ? owner.getTaskName()
                : UNKNOWN_OWNER_TASK_NAME;
    }

    private static String formatReleaseTime(Long taskEndAt) {
        return RELEASE_TIME_FORMAT.format(Instant.ofEpochMilli(taskEndAt));
    }
}
