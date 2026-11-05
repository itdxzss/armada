package com.armada.account.mapper;

import com.armada.account.model.dto.AccountMutualChatQuery;
import com.armada.account.model.dto.AccountMutualChatWork;
import com.armada.account.model.entity.AccountMutualChatItem;
import com.armada.account.model.entity.AccountMutualChatTask;
import com.armada.account.model.vo.AccountMutualChatStatsVO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 互聊任务真实 SQL；前台查询受租户插件保护，扫描只返回调度主键。 */
@Mapper
public interface AccountMutualChatMapper {
    /** 读取当前租户任务。 */
    AccountMutualChatTask task(@Param("id") Long id);
    /** 锁定任务，串行化停止、重试、派发与回执。 */
    AccountMutualChatTask lockTask(@Param("id") Long id);
    /** 按发起人和请求键识别重复创建。 */
    AccountMutualChatTask byRequest(@Param("userId") long userId, @Param("requestId") String requestId);
    /** 写入冻结的任务配置。 */
    int insertTask(AccountMutualChatTask task);
    /** 批量写入单方向发送事实。 */
    int insertItems(@Param("items") List<AccountMutualChatItem> items);
    /** 按用户归属统计可见任务。 */
    long countTasks(@Param("ownerId") Long ownerId);
    /** 在 SQL 中分页读取可见任务。 */
    List<AccountMutualChatTask> tasks(@Param("ownerId") Long ownerId, @Param("q") AccountMutualChatQuery q);
    /** 现场聚合状态与双向成功对数。 */
    AccountMutualChatStatsVO stats(@Param("taskId") Long taskId);
    /** 按状态统计本任务方向数。 */
    long countItems(@Param("taskId") Long taskId, @Param("q") AccountMutualChatQuery q);
    /** 在 SQL 中分页读取单方向事实。 */
    List<AccountMutualChatItem> items(@Param("taskId") Long taskId, @Param("q") AccountMutualChatQuery q);
    /** 读取当前租户单方向事实。 */
    AccountMutualChatItem item(@Param("id") Long id);
    /** 按当前命令匹配回执，重试后旧命令不再命中。 */
    AccountMutualChatItem byCommand(@Param("commandId") String commandId);
    /** 返回本账号在任务内的首条待发送方向。 */
    AccountMutualChatItem firstPending(@Param("taskId") Long taskId, @Param("actorId") Long actorId);
    /** 检查本功能跨任务的账号在途与冷却。 */
    long actorBlocked(@Param("actorId") Long actorId, @Param("now") long now);
    /** 更新当前方向的命令和发送结果。 */
    int updateItem(AccountMutualChatItem item);
    /** 同步任务执行状态。 */
    int setTaskStatus(@Param("id") Long id, @Param("status") int status, @Param("now") long now);
    /** 停止任务时仅取消未提交方向。 */
    int cancelPending(@Param("taskId") Long taskId, @Param("now") long now);
    /** 仅重排失败、待确认和已取消方向，清除旧命令关联。 */
    int retryFailed(@Param("taskId") Long taskId, @Param("now") long now);
    /** 超时转待确认并按结果处理时间计算冷却。 */
    int expireSubmitted(@Param("taskId") Long taskId, @Param("deadline") long deadline, @Param("now") long now);
    /** 无租户的后台调度只拿租户/任务/执行方 ID，不返回业务内容。 */
    @InterceptorIgnore(tenantLine = "true")
    List<AccountMutualChatWork> scan(@Param("limit") int limit, @Param("now") long now);
    /** 回执超时扫描，包含已停止任务的在途操作。 */
    @InterceptorIgnore(tenantLine = "true")
    List<AccountMutualChatWork> expired(@Param("deadline") long deadline, @Param("limit") int limit);
}
