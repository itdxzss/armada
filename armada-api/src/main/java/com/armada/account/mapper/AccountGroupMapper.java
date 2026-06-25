package com.armada.account.mapper;

import com.armada.account.model.dto.AccountGroupQuery;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.model.vo.AccountGroupVoRow;
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
     * 批量软删除分组:将 deleted_at 置为 deletedAt 时间戳。
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
}
