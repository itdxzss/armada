package com.armada.account.mapper;

import com.armada.account.model.dto.AccountRegistrationQuery;
import com.armada.account.model.entity.AccountRegistrationItem;
import com.armada.account.model.entity.AccountRegistrationTask;
import com.armada.account.model.vo.AccountRegistrationCountsVO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 接码注册两张同聚合表的数据访问，普通操作由租户插件约束。 */
@Mapper
public interface AccountRegistrationMapper {
    /** 按幂等键查询当前租户任务。 */
    AccountRegistrationTask findByRequestId(String requestId);
    /** 查询当前租户任务。 */
    AccountRegistrationTask findTask(Long id);
    /** 保存任务定义并回填主键。 */
    int insertTask(AccountRegistrationTask task);
    /** 保存固定数量明细，不支持执行时追加采购次数。 */
    int insertItems(@Param("items") List<AccountRegistrationItem> items);
    /** 当前租户任务数。 */
    long countTasks();
    /** 数据库分页任务定义。 */
    List<AccountRegistrationTask> listTasks(AccountRegistrationQuery query);
    /** 聚合当前租户单任务的状态计数。 */
    AccountRegistrationCountsVO counts(Long taskId);
    /** 当前租户任务的最多100条固定明细。 */
    List<AccountRegistrationItem> listItems(Long taskId);
    /** 当前租户的一条明细。 */
    AccountRegistrationItem findItem(Long id);
    /** 原子标记停止未来采购。 */
    int requestCancel(@Param("taskId") Long taskId, @Param("now") long now);
    /** 仅取消仍待采购的明细，已采购不宣称退款。 */
    int cancelPending(@Param("taskId") Long taskId, @Param("now") long now);
    /** 跨租户后台只扫描一条ID及租户ID；已在途项优先，随后恢复租户上下文。 */
    @InterceptorIgnore(tenantLine = "true")
    AccountRegistrationItem nextWork();
    /** 使用原状态与过期租约条件抢占单条工作；租户插件继续生效。 */
    int claim(@Param("item") AccountRegistrationItem item, @Param("now") long now);
    /** 每次外部调用前/后按令牌且租约未过期保存，防止旧worker覆盖新结果。 */
    int updateClaimed(AccountRegistrationItem item);
    /** 仅释放本人令牌，不能清掉其他worker的租约。 */
    int release(@Param("id") Long id, @Param("token") String token);
}
