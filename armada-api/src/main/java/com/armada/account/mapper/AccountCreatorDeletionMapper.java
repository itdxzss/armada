package com.armada.account.mapper;

import com.armada.account.model.dto.CreatorDeletionBinding;
import com.armada.account.model.dto.CreatorActiveScriptBinding;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCreatorDeletion;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 一次性建群账号的排他生命周期。所有入口显式绑定租户；身份唯一键跨租户。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface AccountCreatorDeletionMapper {
    /** 按规范化身份锁定所有别名账号，跨租户仅用于串行化同一 WhatsApp 身份。 */
    List<Long> lockIdentityAliases(@Param("phone") String normalizedPhone);
    /** 锁定当前租户账号身份，串行化预留与注销。 */
    Account lockAccount(@Param("tenantId") long tenantId, @Param("accountId") long accountId);
    /** 在线 Android 主设备候选；不读取或返回明文凭据。 */
    Account eligibleAccount(@Param("tenantId") long tenantId, @Param("accountId") long accountId);
    /** 配置阶段只校验分组中是否有支持的身份，最终以预留时复核为准。 */
    int supportedGroupCount(@Param("tenantId") long tenantId, @Param("groupId") long groupId);
    /** 查询本执行行冻结记录。 */
    AccountCreatorDeletion byExecution(@Param("tenantId") long tenantId, @Param("executionId") long executionId);
    /** 插入全局身份唯一的账号预留；冲突由数据库拒绝。 */
    int insert(AccountCreatorDeletion row);
    /** 同一事务持有全部身份账号锁后执行当前读；仅返回活跃依赖，不暴露其他租户数据。 */
    boolean hasOtherDependencies(AccountCreatorDeletion row);
    /** 持有身份账号锁后的当前读；只核对活跃剧本冻结角色，不以模板或账号组代替依赖。 */
    List<CreatorActiveScriptBinding> activeScriptBindings(@Param("phone") String normalizedPhone);
    /** 在不可变归属一致时把预留推进为不可逆注销中。 */
    int begin(@Param("binding") CreatorDeletionBinding binding, @Param("now") long now);
    /** 全部证据通过后更新生命周期，不删除身份或关联。 */
    int complete(@Param("binding") CreatorDeletionBinding binding, @Param("now") long now);
    /** 禁止自动上线并记录注销生命周期来源。 */
    int freezeOnline(@Param("tenantId") long tenantId, @Param("accountId") long accountId, @Param("now") long now);
    /** 完成注销时更新实际离线状态。 */
    int markOffline(@Param("tenantId") long tenantId, @Param("accountId") long accountId, @Param("now") long now);
    /** 保留角色审计，只释放本执行行建群角色的资源占用。 */
    int releaseCreator(@Param("binding") CreatorDeletionBinding binding, @Param("now") long now);
}
