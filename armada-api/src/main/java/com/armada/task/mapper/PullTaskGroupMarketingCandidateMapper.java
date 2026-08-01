package com.armada.task.mapper;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import com.armada.task.model.dto.PullTaskGroupMarketingCandidateQuery;
import com.armada.task.model.vo.PullTaskGroupMarketingCandidateAccountRow;
import com.armada.task.model.vo.PullTaskGroupMarketingCandidateRow;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 拉群营销候选群组跨表只读 Mapper。 */
@Mapper
public interface PullTaskGroupMarketingCandidateMapper {

    /**
     * 统计符合筛选条件且按 JID 去重后的群组数量。
     *
     * @param query 候选筛选条件
     * @return 去重后的群组数量
     */
    default long countPage(PullTaskGroupMarketingCandidateQuery query) {
        return countPageByTenant(requireTenantId(), query);
    }

    /** 显式租户版候选统计，用于绕过 JSON_TABLE 的租户 SQL 自动改写。 */
    @InterceptorIgnore(tenantLine = "true")
    long countPageByTenant(
            @Param("tenantId") Long tenantId,
            @Param("query") PullTaskGroupMarketingCandidateQuery query);

    /**
     * 分页查询候选群组聚合事实。
     *
     * @param query 候选筛选条件
     * @param offset SQL 偏移量
     * @param limit 最大返回数
     * @return 按 JID 排序的候选群组
     */
    default List<PullTaskGroupMarketingCandidateRow> selectPage(
            PullTaskGroupMarketingCandidateQuery query,
            int offset,
            int limit) {
        return selectPageByTenant(requireTenantId(), query, offset, limit);
    }

    /** 显式租户版候选分页，用于绕过 JSON_TABLE 的租户 SQL 自动改写。 */
    @InterceptorIgnore(tenantLine = "true")
    List<PullTaskGroupMarketingCandidateRow> selectPageByTenant(
            @Param("tenantId") Long tenantId,
            @Param("query") PullTaskGroupMarketingCandidateQuery query,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /**
     * 批量读取当前页各群全部可操作管理员账号。
     *
     * @param groupJids 当前页群 JID
     * @return 在线优先的账号行
     */
    List<PullTaskGroupMarketingCandidateAccountRow> selectAccountsByGroupJids(
            @Param("groupJids") List<String> groupJids);

    /**
     * 按 JID 重新读取加入等待池所需的候选事实。
     *
     * @param groupJids 待重新校验群 JID
     * @return 仍存在本地快照的群组
     */
    default List<PullTaskGroupMarketingCandidateRow> selectByGroupJids(
            List<String> groupJids) {
        return selectByGroupJidsByTenant(requireTenantId(), groupJids);
    }

    /** 显式租户版候选复核，用于绕过 JSON_TABLE 的租户 SQL 自动改写。 */
    @InterceptorIgnore(tenantLine = "true")
    List<PullTaskGroupMarketingCandidateRow> selectByGroupJidsByTenant(
            @Param("tenantId") Long tenantId,
            @Param("groupJids") List<String> groupJids);

    /** 读取当前可信租户，缺失时拒绝执行显式租户 SQL。 */
    private static Long requireTenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        return tenantId;
    }
}
