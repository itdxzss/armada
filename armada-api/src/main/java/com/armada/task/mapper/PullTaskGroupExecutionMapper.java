package com.armada.task.mapper;

import com.armada.task.model.entity.PullTaskGroupExecution;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 群链接执行行数据访问层。 */
@Mapper
public interface PullTaskGroupExecutionMapper {

    /**
     * 创建页生成匹配计划时写入草稿执行行。
     *
     * <p>草稿行 {@code execution_status=0}，生成列 {@code link_occupancy_key} 为 NULL，
     * 因此不同用户的草稿可以同时持有同一条群链接（ADR-0007）。</p>
     *
     * <p>底层 SQL 把 {@code execution_status, stage, manual_paused, next_manager_index,
     * next_puller_index, next_run_at, version} 这七列强制写死为草稿初始值
     * (分别是 0, 1, 0, 0, 0, 0, 1)，不从 {@code row} 绑定参数；调用方在 {@code row} 上对这七个
     * 字段设置的任何值都会被忽略，不会写入数据库。这是有意为之——一个叫 {@code insertDraft}
     * 的方法应当总是产出草稿行，但调用方不应假设这些 setter 在这里有效。</p>
     *
     * @param row 草稿执行行；写入后回填 id
     * @return 新增行数
     */
    int insertDraft(PullTaskGroupExecution row);

    /**
     * 读取任务的全部执行行，按 seq 升序。
     *
     * @param taskId 拉群任务 ID
     * @return 执行行列表
     */
    List<PullTaskGroupExecution> selectByTaskId(@Param("taskId") long taskId);

    /**
     * 删除任务下尚未冻结的草稿执行行，支撑创建页的"清除全部"。
     *
     * @param taskId 拉群任务 ID
     * @return 删除行数
     */
    int deleteDraftByTaskId(@Param("taskId") long taskId);

    /**
     * 删除草稿任务下的单条执行行。
     *
     * <p>带 {@code execution_status = 0} 守卫，已冻结的执行行删不掉。
     * 调用方必须先删该行的料子成员：若本方法返回 0，事务回滚会把料子恢复。</p>
     *
     * @param taskId 草稿任务 ID
     * @param rowId  执行行 ID
     * @return 实际删除行数；0 表示行不存在、不属于该任务或已冻结
     */
    int deleteDraftRow(@Param("taskId") long taskId, @Param("rowId") long rowId);

    /**
     * 查出这批链接里已被本租户运行中任务占用的部分。
     *
     * <p>占用口径与生成列 {@code link_occupancy_key} 一致：{@code execution_status} 为
     * 1（待启动）、2（运行中）、3（暂停）时占用，草稿与终态不占用。这是创建页的软提示，
     * 硬互斥由唯一键在提交时承担。</p>
     *
     * <p><b>调用方必须保证 links 非空</b>：空集合会让 {@code foreach} 生成非法 SQL。</p>
     *
     * @param links 待检查的归一化链接，非空
     * @return 已被占用的归一化链接；无占用时为空列表
     */
    List<String> selectOccupiedLinks(@Param("links") List<String> links);

    /**
     * 任务由草稿冻结为待启动时，把本任务的草稿执行行整体推进为待启动。
     *
     * <p>推进后生成列 {@code link_occupancy_key} 取到链接值，占用随之生效；
     * 若同一链接已被另一个在跑的任务占用，数据库唯一键会抛
     * {@link org.springframework.dao.DuplicateKeyException}，调用方应把它翻译成
     * 面向运营的"群链接已被占用"业务异常。</p>
     *
     * @param taskId 拉群任务 ID
     * @param now 冻结时间(epoch 毫秒)
     * @return 实际冻结行数
     */
    int freezeDraftRows(@Param("taskId") long taskId, @Param("now") long now);

    /**
     * 调度器跨租户抢占到期的执行行。
     *
     * <p>后台调度线程没有租户上下文（{@code MyBatisConfig} 无上下文时 fail-closed
     * 回退 -1），因此这里忽略租户拦截，并只走不带租户前缀的
     * {@code idx_pull_task_execution_dispatch} 索引。</p>
     *
     * @param limit 单批最多抢占行数
     * @param now 当前时间(epoch 毫秒)
     * @param lockOwner 抢占实例标识
     * @param lockExpiresAt 本次锁过期时间(epoch 毫秒)
     * @return 实际抢占行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int claimDue(@Param("limit") int limit,
                 @Param("now") long now,
                 @Param("lockOwner") String lockOwner,
                 @Param("lockExpiresAt") long lockExpiresAt);

    /**
     * 读取本实例当前持有且租约仍未过期的执行行。
     *
     * <p>只按 {@code lock_owner} 过滤是不够的：租约静默过期后，若尚未有别的实例把它抢走，
     * 这里仍会把该行当作"本实例持有"返回。因此额外要求 {@code lock_expires_at > now}，
     * 让过期的持有在被真正抢占之前就已经对原持有者不可见。</p>
     *
     * @param lockOwner 抢占实例标识
     * @param now 当前时间(epoch 毫秒)
     * @return 该实例当前持有且租约未过期的执行行
     */
    @InterceptorIgnore(tenantLine = "true")
    List<PullTaskGroupExecution> selectClaimed(@Param("lockOwner") String lockOwner,
                                               @Param("now") long now);

    /**
     * 用乐观锁推进执行行的检查点。
     *
     * <p>本方法未加 {@code @InterceptorIgnore}，因此会被注入 {@code AND tenant_id = ?}。
     * 调用方必须在调用前把 {@link com.armada.shared.tenant.TenantContext} 设置为该行所属的
     * 租户——通过 {@code claimDue}/{@code selectClaimed} 这两个跨租户方法拿到的行不自带租户
     * 上下文，调度器必须先从行内 {@code tenantId} 恢复上下文再调用本方法。若上下文缺失或与
     * 该行租户不一致，注入的条件会退化为哨兵 -1，匹配不到任何行。</p>
     *
     * @param id 执行行 ID
     * @param expectedVersion 读取时拿到的版本号
     * @param nextManagerIndex 新的管理账号轮询游标
     * @param nextPullerIndex 新的拉手轮询游标
     * @param stage 新的业务阶段
     * @param nextRunAt 下次可调度时间(epoch 毫秒)
     * @param now 更新时间(epoch 毫秒)
     * @return 实际更新行数；0 表示版本已过期，或调用时缺失/错配租户上下文
     */
    int updateCheckpoint(@Param("id") long id,
                         @Param("expectedVersion") int expectedVersion,
                         @Param("nextManagerIndex") Integer nextManagerIndex,
                         @Param("nextPullerIndex") Integer nextPullerIndex,
                         @Param("stage") Integer stage,
                         @Param("nextRunAt") long nextRunAt,
                         @Param("now") long now);

    /**
     * 释放本实例持有的调度锁。
     *
     * @param id 执行行 ID
     * @param lockOwner 抢占实例标识
     * @param now 释放时间(epoch 毫秒)，写入 {@code updated_at}
     * @return 实际释放行数
     */
    @InterceptorIgnore(tenantLine = "true")
    int releaseLock(@Param("id") long id, @Param("lockOwner") String lockOwner, @Param("now") long now);
}
