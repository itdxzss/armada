package com.armada.marketing.service.impl;

import com.armada.marketing.mapper.MarketingAccountOccupancyMapper;
import com.armada.marketing.model.entity.MarketingTask;
import com.armada.marketing.model.vo.MarketingAccountOccupancyOwnerRow;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 普通群组营销账号当前占用领域服务。
 *
 * <p>占用粒度是账号，不存在分组锁。创建任务时的“分组占用”只是查询该分组内是否已有
 * 账号租约；执行期由数据库唯一键保证同租户同账号只有一个普通营销任务持有。</p>
 */
@Service
public class MarketingAccountOccupancyService {

    private static final Logger log = LoggerFactory.getLogger(MarketingAccountOccupancyService.class);
    private static final String GENERIC_GROUP_OCCUPIED_MESSAGE =
            "该分组正在执行其它营销任务，请等待当前任务结束后再参与新的营销任务。";
    private static final String GENERIC_ATTEMPT_OCCUPIED_MESSAGE =
            "账号正在被其它营销任务占用，本轮未发送。";
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
     * 校验账号分组当前没有被普通营销任务占用的账号。
     *
     * <p>这里不创建分组锁；只要分组内任意账号存在有效租约，就拒绝本次任务创建或账号树加载。</p>
     *
     * @param accountGroupId 账号分组 ID
     * @param now            当前时间(epoch毫秒)
     * @throws BusinessException 分组内存在被占用账号时抛出可直接展示的业务提示
     */
    @Transactional(rollbackFor = Exception.class)
    public void assertAccountGroupAvailable(Long accountGroupId, long now) {
        int staleDeleted = mapper.deleteStale(now);
        MarketingAccountOccupancyOwnerRow owner = mapper.selectFirstOwnerByAccountGroupId(accountGroupId);
        if (owner == null) {
            log.debug("营销账号分组占用校验通过 accountGroupId={} staleDeleted={}", accountGroupId, staleDeleted);
            return;
        }
        String message = groupOccupiedMessage(owner);
        log.warn("营销账号分组占用校验拒绝 accountGroupId={} ownerTaskId={} accountId={} taskEndAt={} staleDeleted={}",
                accountGroupId, owner.getMarketingTaskId(), owner.getAccountId(), owner.getTaskEndAt(), staleDeleted);
        throw new BusinessException(ErrorCode.CONFLICT, message);
    }

    /**
     * 抢占指定发送中任务当前空闲的目标账号，并返回所有目标账号的当前占用方。
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

    private static String groupOccupiedMessage(MarketingAccountOccupancyOwnerRow owner) {
        if (!hasOwnerReleaseDetails(owner)) {
            return GENERIC_GROUP_OCCUPIED_MESSAGE;
        }
        return "该分组已被营销任务【" + owner.getTaskName() + "】占用，预计于【"
                + formatReleaseTime(owner.getTaskEndAt())
                + "】释放，请稍后重试。";
    }

    private static boolean hasOwnerReleaseDetails(MarketingAccountOccupancyOwnerRow owner) {
        return owner != null && StringUtils.hasText(owner.getTaskName()) && owner.getTaskEndAt() != null;
    }

    private static String formatReleaseTime(Long taskEndAt) {
        return RELEASE_TIME_FORMAT.format(Instant.ofEpochMilli(taskEndAt));
    }
}
