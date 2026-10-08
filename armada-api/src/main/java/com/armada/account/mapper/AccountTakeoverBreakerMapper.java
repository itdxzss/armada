package com.armada.account.mapper;

import com.armada.account.model.entity.AccountTakeoverBreaker;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 自动抢登熔断事实的数据访问；账号锁显式限定租户，其他 SQL 由租户插件约束。 */
@Mapper
public interface AccountTakeoverBreakerMapper {

    /** 锁定当前租户存续账号；显式租户条件避免解析器重排 ORDER BY / FOR UPDATE。 */
    @InterceptorIgnore(tenantLine = "true")
    List<Long> lockAccountIds(@Param("tenantId") long tenantId, @Param("accountIds") List<Long> accountIds);

    /** 查询当前租户账号的熔断快照，未建立窗口时返回空。 */
    AccountTakeoverBreaker selectByAccountId(@Param("accountId") Long accountId);

    /** 持有账号锁后当前读熔断行，避免旧一致性快照覆盖锁等待期间提交的计数。 */
    AccountTakeoverBreaker selectForUpdateByAccountId(@Param("accountId") Long accountId);

    /** 为账号首次计数插入唯一窗口，tenant_id 由租户插件注入。 */
    int insert(AccountTakeoverBreaker row);

    /** 持有账号锁与熔断行锁后更新窗口及熔断状态。 */
    int update(AccountTakeoverBreaker row);

    /** 清空人工选定账号的窗口与熔断状态；调用者先按账号顺序加锁。 */
    int reset(@Param("accountIds") List<Long> accountIds, @Param("now") long now);
}
