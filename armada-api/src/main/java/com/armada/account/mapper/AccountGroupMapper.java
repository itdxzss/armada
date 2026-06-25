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

    /** 分页总数(与 selectPage 共用 filter,口径一致)。 */
    long countPage(AccountGroupQuery query);

    /** 分页列表,含聚合活跃账号数。 */
    List<AccountGroupVoRow> selectPage(AccountGroupQuery query);

    /** 按名称查活跃分组(校验重名/复活判断)。 */
    AccountGroup selectActiveByName(@Param("name") String name);

    /** 按名称查软删分组(复活场景)。 */
    AccountGroup selectDeletedByName(@Param("name") String name);

    /** 按 ID 查活跃分组。 */
    AccountGroup selectById(@Param("id") Long id);

    /** 查系统内置分组(system_builtin=1)。 */
    AccountGroup selectSystemBuiltin();

    /** 插入新分组(id/tenant_id 由库/拦截器注入;时间由调用方显式传入)。 */
    int insert(AccountGroup row);

    /** 复活软删分组(deleted_at=NULL)。 */
    int reviveById(@Param("id") Long id);

    /** 更新分组基本信息(name/remark/updatedAt)。 */
    int updateProfile(AccountGroup row);

    /** 批量软删除(deletedAt 由调用方传入 System.currentTimeMillis())。 */
    int softDeleteByIds(@Param("ids") List<Long> ids, @Param("deletedAt") long deletedAt);

    /** 查分组下活跃账号数(account.deleted_at IS NULL)。 */
    long countAccountsByGroupId(@Param("groupId") Long groupId);
}
