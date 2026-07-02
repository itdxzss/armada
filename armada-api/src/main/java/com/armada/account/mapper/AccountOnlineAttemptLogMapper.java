package com.armada.account.mapper;

import com.armada.account.model.entity.AccountOnlineAttemptLog;
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

    /**
     * 删除当前 TenantContext 租户内早于 cutoff 的诊断日志。
     */
    int deleteBefore(@Param("cutoff") LocalDateTime cutoff,
                     @Param("limit") int limit);
}
