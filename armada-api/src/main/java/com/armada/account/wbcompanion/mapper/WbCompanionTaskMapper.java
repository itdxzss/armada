package com.armada.account.wbcompanion.mapper;

import com.armada.account.wbcompanion.model.entity.WbCompanionTask;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 登录到 WB 分身任务（wb_companion_task）的数据访问；除到期扫描外都受租户拦截。 */
@Mapper
public interface WbCompanionTaskMapper {

    /** 新建进行中的任务；同一账号已有进行中任务时唯一键冲突。 */
    int insert(WbCompanionTask task);

    /** 当前租户下按 ID 读取任务。 */
    WbCompanionTask selectById(@Param("id") Long id);

    /** 跨租户读取到期的进行中任务，只返回 ID 和租户，调度器逐条切换租户后再推进。 */
    @InterceptorIgnore(tenantLine = "true")
    List<WbCompanionTask> selectDue(@Param("now") long now, @Param("limit") int limit);

    /**
     * 抢占推进租约：只有 next_run_at 仍是读到的值时才把它推迟到租约到期，多个实例只会有一个推进成功。
     *
     * @return 1 表示抢到
     */
    int claim(@Param("id") Long id, @Param("expected") long expected, @Param("leaseUntil") long leaseUntil,
              @Param("now") long now);

    /** 保存推进结果；只更新仍在进行中的任务，已停止的任务不会被推进结果覆盖。 */
    int updateProgress(WbCompanionTask task);

    /** 操作人停止进行中的任务。 */
    int stop(@Param("id") Long id, @Param("now") long now);

    /** 账号最近的任务，新的在前。 */
    List<WbCompanionTask> selectRecentByAccount(@Param("accountId") Long accountId, @Param("limit") int limit);

    /** 这些账号各自进行中的任务。 */
    List<WbCompanionTask> selectRunningByAccountIds(@Param("accountIds") List<Long> accountIds);
}
