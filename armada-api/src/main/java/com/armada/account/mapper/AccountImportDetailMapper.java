package com.armada.account.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.armada.account.model.dto.AccountImportDetailQuery;
import com.armada.account.model.entity.AccountImportDetail;
import com.armada.account.model.entity.AccountImportLoginResultSettlement;
import com.armada.account.model.vo.AccountImportDetailVoRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 账号导入明细数据访问。tenant_id 由租户行隔离拦截器自动注入。
 */
@Mapper
public interface AccountImportDetailMapper {

    /**
     * 批量插入明细行(&lt;foreach&gt; 多值 INSERT)。
     * tenant_id 由拦截器注入,不手写。
     */
    int batchInsert(@Param("rows") List<AccountImportDetail> rows);

    /**
     * 跨租户扫描存在待派发导入明细的租户 ID。
     *
     * <p>后台 job 没有天然租户上下文,这里关闭租户拦截器只读取 tenant_id,随后按租户重建上下文。</p>
     *
     * @param queuedPhase        待派发阶段码
     * @param successParseResult 成功导入 parse_result 码
     * @param limit              最多返回租户数
     * @return 有待派发明细的租户 ID 列表
     */
    @InterceptorIgnore(tenantLine = "true")
    List<Long> selectQueuedTenantIds(@Param("queuedPhase") int queuedPhase,
                                     @Param("successParseResult") int successParseResult,
                                     @Param("limit") int limit);

    /**
     * 锁定指定租户最多一批待派发导入明细。
     *
     * <p>使用 {@code LIMIT ... FOR UPDATE} 时关闭租户拦截器,SQL 显式写 {@code tenant_id = ?},
     * 避免租户 SQL 改写破坏 MySQL 锁行语法。</p>
     *
     * @param tenantId           租户 ID
     * @param queuedPhase        待派发阶段码
     * @param successParseResult 成功导入 parse_result 码
     * @param limit              批量上限
     * @return 已锁定的待派发明细
     */
    @InterceptorIgnore(tenantLine = "true")
    List<AccountImportDetail> selectQueuedForUpdate(@Param("tenantId") Long tenantId,
                                                    @Param("queuedPhase") int queuedPhase,
                                                    @Param("successParseResult") int successParseResult,
                                                    @Param("limit") int limit);

    /**
     * 将待派发明细推进到已派发阶段。
     *
     * @param ids             明细 ID 列表
     * @param queuedPhase     待派发阶段码
     * @param dispatchedPhase 已派发阶段码
     * @param dispatchedAt    派发时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int markDispatched(@Param("ids") List<Long> ids,
                       @Param("queuedPhase") int queuedPhase,
                       @Param("dispatchedPhase") int dispatchedPhase,
                       @Param("dispatchedAt") long dispatchedAt);

    /**
     * 将已派发上线命令的导入明细冻结为首次登录终态。
     *
     * <p>只更新 {@code online_phase=DISPATCHED} 且 {@code login_result IS NULL} 的成功导入明细。
     * {@code online_dispatched_at &lt;= loginSettledAt} 用于避免旧状态事件结算到本次导入之前的派发。</p>
     *
     * @param settlement 登录结果结算参数
     * @return 更新行数;普通上线无导入明细时为 0
     */
    int settleDispatchedLoginResult(@Param("settlement") AccountImportLoginResultSettlement settlement);

    /**
     * 按批次 ID 和结果过滤器统计明细总数(SQL 下推)。
     *
     * @param query 明细查询参数(batchId 必传,filter = all/success/fail)
     * @return 符合条件的明细总数
     */
    long countByBatch(AccountImportDetailQuery query);

    /**
     * 按批次 ID 分页查询明细列表(JOIN account_import_batch 取 groupName)。
     *
     * @param query 明细查询参数(含 offset / pageSize / filter)
     * @return 当前页明细 VoRow 列表
     */
    List<AccountImportDetailVoRow> selectPageByBatch(AccountImportDetailQuery query);

    /**
     * 按批次 ID 查全部明细(不分页),用于 CSV 导出。
     *
     * @param batchId 批次 ID
     * @param scope   结果范围:all=全部;success=只成功(parse_result=1);fail=只失败(parse_result IN 2,3,4)
     * @return 符合条件的明细 VoRow 列表
     */
    List<AccountImportDetailVoRow> selectAllByBatch(@Param("batchId") Long batchId, @Param("scope") String scope);
}
