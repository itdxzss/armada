package com.armada.account.mapper;

import com.armada.account.model.dto.AccountTierCheckCandidate;
import com.armada.account.model.dto.AccountTierCheckQuery;
import com.armada.account.model.dto.AccountTierCheckRequestDTO;
import com.armada.account.model.dto.AccountTierCheckWork;
import com.armada.account.model.entity.AccountTierCheckTask;
import com.armada.account.model.entity.AccountTierCheckItem;
import com.armada.account.model.vo.AccountTierCheckStatsVO;
import com.armada.account.model.vo.AccountTierCheckTierCountVO;
import com.armada.account.model.vo.AccountTierCheckTimelineEntryVO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 档位查询持久事实；业务查询受租户插件保护，后台跨租户仅扫描主键。 */
@Mapper
public interface AccountTierCheckMapper {
    /** 有界候选，缺少账号状态不妨碍探针查询目标号码。 */
    List<AccountTierCheckCandidate> candidates(@Param("req") AccountTierCheckRequestDTO req, @Param("limit") int limit);
    /** 当前账号/协议/凭据版本快照。 */
    AccountTierCheckCandidate account(@Param("id") Long id);
    /** 排他账号锁，跨任务/进程串行同发起账号。 */
    Long lockAccount(@Param("id") Long id);
    /** 锁凭据版本以阻止结果校验与入库之间的凭据变更。 */
    Long lockCredential(@Param("id") Long id);
    /** 确认分组均在本租户且未删除。 */
    long countGroups(@Param("ids") List<Long> ids);
    /** 保存批次，返回自增主键。 */
    int insertTask(AccountTierCheckTask task);
    /** 有界批量冻结目标快照。 */
    int insertItems(@Param("items") List<AccountTierCheckItem> items);
    /** 批次读取。 */
    AccountTierCheckTask task(@Param("id") Long id);
    /** 批次写操作行锁。 */
    AccountTierCheckTask lockTask(@Param("id") Long id);
    /** 当前租户发起人的幂等请求。 */
    AccountTierCheckTask byRequest(@Param("userId") long userId, @Param("requestId") String requestId);
    /** 带相同筛选条件的分页总数。 */
    long countTasks(@Param("ownerId") Long ownerId, @Param("q") AccountTierCheckQuery q);
    /** SQL 分页，禁止内存分页。 */
    List<AccountTierCheckTask> tasks(@Param("ownerId") Long ownerId, @Param("q") AccountTierCheckQuery q);
    /** 批次归一化结果统计。 */
    AccountTierCheckStatsVO stats(@Param("taskId") Long taskId);
    /** 仅成功快照计入档位分布。 */
    List<AccountTierCheckTierCountVO> tierCounts(@Param("taskId") Long taskId);
    /** 明细分页总数。 */
    long countItems(@Param("taskId") Long taskId, @Param("q") AccountTierCheckQuery q);
    /** SQL 下推明细分页/变化筛选。 */
    List<AccountTierCheckItem> items(@Param("taskId") Long taskId, @Param("q") AccountTierCheckQuery q);
    /** 单条快照读取。 */
    AccountTierCheckItem item(@Param("id") Long id);
    /** 已完成快照中最近的成功记录，排除本条。 */
    AccountTierCheckItem previousSuccess(@Param("accountId") Long accountId, @Param("excludeId") Long excludeId);
    /** 单号时间线按数据归属隔离；已成功快照永不覆盖。 */
    List<AccountTierCheckTimelineEntryVO> history(@Param("accountId") Long accountId, @Param("ownerId") Long ownerId);
    /** 复查冻结的同一批账号 ID，不读取其他批次。 */
    List<Long> accountIds(@Param("taskId") Long taskId);
    /** 发起账号是否在途或冷却。调用前先锁账号。 */
    long executorBlocked(@Param("executorId") Long executorId, @Param("now") long now);
    /** 取发起账号前次派发轮数与冷却水位。 */
    AccountTierCheckItem latestExecutor(@Param("executorId") Long executorId);
    /** 探针按在途数量和等待水位分配。 */
    long executorLoad(@Param("executorId") Long executorId);
    /** 原子待查到查询中，并推进租约水位，attemptNo 隔离旧结果。 */
    int claim(AccountTierCheckItem item);
    /** 当前尝试的归一化结果/延期；WHERE 状态和尝试号拒绝晚到回执。 */
    int finish(AccountTierCheckItem item);
    /** 不发查询时也持久化确定失败，保留前次成功快照。 */
    int failPending(AccountTierCheckItem item);
    /** 繁忙执行方短延期并轮转扫描顺序，避免阻塞其他批次。 */
    int defer(@Param("id") Long id, @Param("nextAt") long nextAt, @Param("now") long now);
    /** 停止只取消待查条目；在途仍可收结果。 */
    int cancelPending(@Param("taskId") Long taskId, @Param("now") long now);
    /** 手动重新排队失败，保留冷却水位和尝试令牌。 */
    int retryFailed(@Param("taskId") Long taskId, @Param("now") long now);
    /** 超时/探针离线回到待查，已停止批次改取消。 */
    int release(@Param("item") AccountTierCheckItem item, @Param("status") int status, @Param("now") long now);
    /** 更新批次状态与完成时间。 */
    int setTaskStatus(@Param("id") Long id, @Param("status") int status, @Param("now") long now);
    /** 跨租户调度只返回主键，线程在业务操作前设置/恢复租户上下文。 */
    @InterceptorIgnore(tenantLine = "true")
    List<AccountTierCheckWork> scan(@Param("now") long now, @Param("limit") int limit);
    /** 包含停止批次；恢复过期认领或已离线探针的未完成项。 */
    @InterceptorIgnore(tenantLine = "true")
    List<AccountTierCheckWork> recoverable(@Param("now") long now, @Param("limit") int limit);
}
