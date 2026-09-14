package com.armada.account.mapper;

import com.armada.account.model.entity.AccountOnlineAttemptLog;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AccountOnlineAttemptLogMapper {

    int insert(AccountOnlineAttemptLog row);

    List<AccountOnlineAttemptLog> selectByAttemptId(@Param("onlineAttemptId") String onlineAttemptId,
                                                    @Param("limit") int limit);

    List<AccountOnlineAttemptLog> selectRecentByAccountId(@Param("accountId") Long accountId,
                                                          @Param("limit") int limit);

    String selectLatestAttemptIdByAccountId(@Param("accountId") Long accountId);

    AccountOnlineAttemptLog selectProxyFailureAt(
            @Param("accountId") Long accountId,
            @Param("occurredAt") LocalDateTime occurredAt);

    /** 持有账号状态锁后进行当前读，避免 MySQL RR 快照漏掉并发已提交的同一失败记录。 */
    @InterceptorIgnore(tenantLine = "true")
    Long selectProxyFailureStateIdForUpdate(
            @Param("tenantId") Long tenantId,
            @Param("accountId") Long accountId,
            @Param("onlineAttemptId") String onlineAttemptId,
            @Param("occurredAt") LocalDateTime occurredAt);

    /**
     * 删除当前 TenantContext 租户内早于 cutoff 的诊断日志。
     */
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff,
                     @Param("limit") int limit);
}
