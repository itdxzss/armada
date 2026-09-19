package com.armada.account.mapper;

import com.armada.account.model.dto.AccountMutualContactCandidate;
import com.armada.account.model.dto.AccountMutualContactQuery;
import com.armada.account.model.dto.AccountMutualContactWork;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.model.entity.AccountMutualContactItem;
import com.armada.account.model.entity.AccountMutualContactTask;
import com.armada.account.model.vo.AccountMutualContactStatsVO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 互存任务真实 SQL；前台查询受租户插件保护，扫描只返回调度主键。 */
@Mapper
public interface AccountMutualContactMapper {
    AccountGroup group(@Param("id") Long id);
    long groupCount(@Param("id") Long id);
    List<AccountMutualContactCandidate> candidates(@Param("groupId") Long groupId, @Param("limit") int limit);
    AccountMutualContactCandidate account(@Param("id") Long id);
    Long lockAccount(@Param("id") Long id);
    AccountMutualContactTask task(@Param("id") Long id);
    AccountMutualContactTask lockTask(@Param("id") Long id);
    AccountMutualContactTask byRequest(@Param("userId") long userId, @Param("requestId") String requestId);
    int insertTask(AccountMutualContactTask task);
    int insertItems(@Param("items") List<AccountMutualContactItem> items);
    long countTasks(@Param("ownerId") Long ownerId);
    List<AccountMutualContactTask> tasks(@Param("ownerId") Long ownerId, @Param("q") AccountMutualContactQuery q);
    AccountMutualContactStatsVO stats(@Param("taskId") Long taskId);
    long countItems(@Param("taskId") Long taskId, @Param("q") AccountMutualContactQuery q);
    List<AccountMutualContactItem> items(@Param("taskId") Long taskId, @Param("q") AccountMutualContactQuery q);
    AccountMutualContactItem item(@Param("id") Long id);
    AccountMutualContactItem firstPending(@Param("taskId") Long taskId, @Param("actorId") Long actorId);
    long actorBlocked(@Param("actorId") Long actorId, @Param("now") long now);
    int updateItem(AccountMutualContactItem item);
    int setTaskStatus(@Param("id") Long id, @Param("status") int status, @Param("now") long now);
    int cancelPending(@Param("taskId") Long taskId, @Param("now") long now);
    int retryFailed(@Param("taskId") Long taskId, @Param("now") long now);
    int expireSubmitted(@Param("taskId") Long taskId, @Param("deadline") long deadline, @Param("now") long now);
    /** 无租户的后台调度只拿租户/任务/执行方 ID，不返回业务内容。 */
    @InterceptorIgnore(tenantLine = "true")
    List<AccountMutualContactWork> scan(@Param("limit") int limit, @Param("now") long now);
    /** 回执超时扫描，包含已停止任务的在途操作。 */
    @InterceptorIgnore(tenantLine = "true")
    List<AccountMutualContactWork> expired(@Param("deadline") long deadline, @Param("limit") int limit);
}
