package com.armada.account.mapper;

import com.armada.account.model.dto.AccountGroupQuery;
import com.armada.account.model.dto.AccountQuery;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.model.vo.AccountGroupVoRow;
import com.armada.account.model.vo.AccountMarketingOccupancyTaskRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 账号分组数据访问。tenant_id 由租户行隔离拦截器自动注入,SQL 不手写 tenant_id 过滤。
 */
@Mapper
public interface AccountGroupMapper {

    /**
     * 按筛选条件统计分组总数(SQL 下推,与 selectPage 共用 filter 片段,口径一致)。
     *
     * @param query 分组列表查询参数(可包含名称关键词等筛选条件)
     * @return 符合条件的分组总数
     */
    long countPage(AccountGroupQuery query);

    /**
     * 按筛选条件分页查询分组列表,含聚合活跃账号数。
     *
     * @param query 分组列表查询参数(含 offset/pageSize 及筛选条件)
     * @return 当前页分组 VoRow 列表(含 accountCount 聚合字段)
     */
    List<AccountGroupVoRow> selectPage(AccountGroupQuery query);

    /**
     * 批量读取当前页占用任务的主状态及拉群资源状态。
     *
     * @param taskIds 当前页去重后的营销任务 ID
     * @return 仍存在的任务状态投影；已删除或异常缺失的任务不返回
     */
    List<AccountMarketingOccupancyTaskRow> selectMarketingOccupancyTasksByIds(
            @Param("taskIds") List<Long> taskIds);

    /**
     * 按营销占用高级条件解析匹配的账号分组 ID。
     *
     * <p>只在高级筛选生效时调用，避免营销任务表进入账号默认分页查询。</p>
     *
     * @param query 账号列表营销占用筛选条件
     * @return 匹配的活跃分组 ID
     */
    List<Long> selectMarketingOccupancyGroupIds(AccountQuery query);

    /**
     * 点击分组名称时查询完整营销占用详情。
     *
     * @param groupId 账号分组 ID
     * @return 占用详情投影；分组不存在或已删除时返回 null
     */
    AccountMarketingOccupancyTaskRow selectMarketingOccupancyByGroupId(@Param("groupId") Long groupId);

    /**
     * 按名称查活跃分组(deleted_at IS NULL)。
     *
     * <p>用于重名校验及复活判断:活跃同名存在则不可新建。</p>
     *
     * @param name 分组名称(精确匹配)
     * @return 活跃分组;不存在时返回 null
     */
    AccountGroup selectActiveByName(@Param("name") String name);

    /**
     * 按名称查软删分组(deleted_at IS NOT NULL)。
     *
     * <p>用于复活场景:同名分组曾被软删时复活并更新,而非插入新行。</p>
     *
     * @param name 分组名称(精确匹配)
     * @return 软删分组;不存在时返回 null
     */
    AccountGroup selectDeletedByName(@Param("name") String name);

    /**
     * 按主键查活跃分组(deleted_at IS NULL)。
     *
     * @param id 分组主键
     * @return 活跃分组;不存在或已软删时返回 null
     */
    AccountGroup selectById(@Param("id") Long id);

    /**
     * 按 ID 升序锁定人工迁移涉及的来源分组和目标分组。
     *
     * @param groupIds 去重并升序排列的分组 ID
     * @return 当前活跃分组行
     */
    List<AccountGroup> selectByIdsForUpdate(@Param("groupIds") List<Long> groupIds);

    /**
     * 统计仍处于资源锁定或释放中的拉群任务对建群账号分组的引用数。
     *
     * @param groupIds 人工迁移的来源分组 ID
     * @return 活动引用数量
     */
    int countActiveBuilderGroupReferences(@Param("groupIds") List<Long> groupIds);

    /**
     * 查系统内置分组(system_builtin=1)。
     *
     * <p>全租户唯一一条;用于懒创建默认分组及导入时的默认分组解析。</p>
     *
     * @return 系统内置分组;不存在时返回 null
     */
    AccountGroup selectSystemBuiltin();

    /**
     * 插入新分组行。
     *
     * <p>id/tenant_id 由库/拦截器注入;时间由调用方显式传入。
     * useGeneratedKeys 回填 id。</p>
     *
     * @param row 待插入的分组实体
     * @return 插入行数(正常为 1)
     */
    int insert(AccountGroup row);

    /**
     * 复活软删分组:将 deleted_at 置为 NULL。
     *
     * @param id 分组主键
     * @return 更新行数(正常为 1)
     */
    int reviveById(@Param("id") Long id);

    /**
     * 更新分组基本信息(name/remark/updatedAt)。
     *
     * @param row 含 id/name/remark/updatedAt 的分组实体
     * @return 更新行数(正常为 1)
     */
    int updateProfile(AccountGroup row);

    /**
     * 批量软删除空闲分组:将 deleted_at 置为 deletedAt 时间戳。
     *
     * <p>SQL 再次要求营销占用任务为空，用作事务校验后的并发条件闸门。</p>
     *
     * @param ids       分组主键列表(已通过业务校验)
     * @param deletedAt 软删时间戳(epoch 毫秒,由调用方传入 System.currentTimeMillis())
     * @return 实际更新行数
     */
    int softDeleteByIds(@Param("ids") List<Long> ids, @Param("deletedAt") long deletedAt);

    /**
     * 统计分组下活跃账号数(account.deleted_at IS NULL)。
     *
     * <p>用于删除前置校验:分组下有账号时不允许删除。</p>
     *
     * @param groupId 分组主键
     * @return 该分组下活跃账号数量
     */
    long countAccountsByGroupId(@Param("groupId") Long groupId);

    /** 按账号 ID 升序查询分组内活跃账号。 */
    List<Long> selectAccountIdsByGroupId(@Param("groupId") Long groupId);

    /** 将指定账号批量迁移到目标分组。 */
    int updateAccountGroup(@Param("accountIds") List<Long> accountIds,
                           @Param("targetGroupId") Long targetGroupId,
                           @Param("updatedAt") long updatedAt);

    /** 将多个来源分组内的全部活跃账号迁移到目标分组。 */
    int mergeAccounts(@Param("sourceGroupIds") List<Long> sourceGroupIds,
                      @Param("targetGroupId") Long targetGroupId,
                      @Param("updatedAt") long updatedAt);

    /**
     * 仅在分组当前空闲时原子写入营销整组占用归属。
     *
     * @return 影响 1 行表示抢锁成功，0 行表示不存在或已被占用
     */
    int tryLockMarketingOccupancy(@Param("groupId") Long groupId,
                                  @Param("occupancyType") int occupancyType,
                                  @Param("taskId") Long taskId,
                                  @Param("now") long now);

    /**
     * 仅由当前占用任务按类型和任务 ID 原子释放营销分组。
     *
     * @return 影响 1 行表示释放成功，0 行表示当前任务不持有该锁
     */
    int releaseMarketingOccupancy(@Param("groupId") Long groupId,
                                  @Param("occupancyType") int occupancyType,
                                  @Param("taskId") Long taskId,
                                  @Param("now") long now);

    /** 查询分组当前是否仍有任意营销占用。 */
    int countMarketingOccupancy(@Param("groupId") Long groupId);
}
