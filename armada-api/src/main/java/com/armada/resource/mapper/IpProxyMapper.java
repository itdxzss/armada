package com.armada.resource.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.armada.resource.model.dto.IpProxyQuery;
import com.armada.resource.model.dto.IpProxyStatsCountryQuery;
import com.armada.resource.model.dto.IpProxyStatsDetailQuery;
import com.armada.resource.model.entity.IpProxy;
import com.armada.resource.model.vo.IpProxyCountrySampleStatsVO;
import com.armada.resource.model.vo.IpProxyCountryStatsRow;
import com.armada.resource.model.vo.IpProxyStatsDetailRow;
import com.armada.resource.model.vo.IpProxyStatsSummaryVO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * IP 代理数据访问。普通 SQL 的 tenant_id 由租户行隔离拦截器自动注入;锁行分配查询显式传 tenantId。
 */
@Mapper
public interface IpProxyMapper {

    /** 列表分页查询（SQL 下推 LIMIT/OFFSET）。 */
    List<IpProxy> selectPage(@Param("q") IpProxyQuery query);

    /** 列表总数（与 selectPage 共用筛选片段，口径一致）。 */
    long countPage(@Param("q") IpProxyQuery query);

    /** IP 数据统计总览。 */
    IpProxyStatsSummaryVO selectStatsSummary();

    /** 国家/地区维度统计总数。 */
    long countCountryStats(@Param("q") IpProxyStatsCountryQuery query);

    /** 国家/地区维度统计分页。 */
    List<IpProxyCountryStatsRow> selectCountryStatsPage(@Param("q") IpProxyStatsCountryQuery query);

    /**
     * 查询国家级抽样检测弹框统计。
     *
     * @param region 国家/地区中文快照
     * @return 启用且支持 IP 管理国家的 IP 状态统计;不存在时返回 null
     */
    IpProxyCountrySampleStatsVO selectCountrySampleStatsByRegion(@Param("region") String region);

    /**
     * 按国家/地区随机抽取未软删 IP 代理 ID。
     *
     * <p>这里不按状态过滤:不可用 IP 也需要参与抽检,以便检测后更新可用性。</p>
     *
     * @param region 国家/地区中文快照
     * @param limit 抽样数量
     * @return 随机抽到的代理 ID
     */
    List<Long> selectSampleActiveIdsByRegion(@Param("region") String region, @Param("limit") int limit);

    /** 指定国家/地区 IP 明细总数。 */
    long countStatsDetail(@Param("region") String region, @Param("q") IpProxyStatsDetailQuery query);

    /** 指定国家/地区 IP 明细分页。 */
    List<IpProxyStatsDetailRow> selectStatsDetailPage(
            @Param("region") String region,
            @Param("q") IpProxyStatsDetailQuery query);

    /**
     * 查询本租户 IP 池已有国家/区域。
     *
     * <p>SQL 层去重并过滤空值,同时把「混合（不限国家）」排在最前,供 IP 管理筛选和账号导入下拉使用。</p>
     */
    List<String> selectDistinctRegions(@Param("mixedRegion") String mixedRegion);

    /** 插入（不含 tenant_id 列，由拦截器注入）。 */
    int insert(IpProxy entity);

    /**
     * 按 ID 查活跃代理行(deleted_at IS NULL)。
     *
     * <p>用于内部确认代理行状态;tenant_id 由租户拦截器注入。</p>
     *
     * @param id 代理主键
     * @return 活跃代理行;不存在或已软删时返回 null
     */
    IpProxy selectActiveById(@Param("id") Long id);

    /**
     * 按 ID 批量查询活跃代理行。
     *
     * @param ids 代理主键列表
     * @return 活跃代理行列表;空列表返回空集合
     */
    List<IpProxy> selectActiveByIds(@Param("ids") List<Long> ids);

    /**
     * 更新代理检测结果。
     *
     * <p>调用方在 entity 中传入本次检测状态、时间、出口详情、失败次数和失败原因。</p>
     *
     * @param entity 包含检测结果的代理实体
     * @param inUseStatus 正在使用状态码;SQL 必须保留该状态,避免检测覆盖占用关系
     * @return 更新行数
     */
    int updateDetectionResult(@Param("entity") IpProxy entity, @Param("inUseStatus") int inUseStatus);

    /**
     * 按国家优先级锁定一条本租户空闲代理。
     *
     * <p>排序规则由调用方传入的 {@code preferredRegion} 决定:有指定国家时优先指定国家,
     * 其次混合池,最后其它国家;无指定国家时混合池优先,其次其它国家。这里显式传 tenantId 并关闭租户拦截器,
     * 避免租户 SQL 改写影响 {@code LIMIT ... FOR UPDATE}。</p>
     */
    @InterceptorIgnore(tenantLine = "true")
    IpProxy selectOneIdleByRegionPriorityForUpdate(@Param("tenantId") Long tenantId,
                                                   @Param("idleStatus") int idleStatus,
                                                   @Param("preferredRegion") String preferredRegion,
                                                   @Param("mixedRegion") String mixedRegion,
                                                   @Param("excludedIds") List<Long> excludedIds);

    /**
     * 将已锁定的空闲代理绑定到账号并置为使用中。
     *
     * <p>WHERE 中保留 status=IDLE 条件,即使调用方漏锁或并发重试也不会覆盖已被占用的代理。</p>
     */
    int markUsingAndBind(@Param("id") Long id,
                         @Param("accountId") Long accountId,
                         @Param("idleStatus") int idleStatus,
                         @Param("usingStatus") int usingStatus,
                         @Param("boundAt") long boundAt);

    /**
     * 批量绑定已锁定的空闲代理。
     *
     * <p>WHERE 保留 status=IDLE 条件兜底,调用方用返回行数确认是否全部绑定成功。</p>
     */
    int markUsingAndBindBatch(@Param("targets") List<IpProxyBindTarget> targets,
                              @Param("idleStatus") int idleStatus,
                              @Param("usingStatus") int usingStatus,
                              @Param("boundAt") long boundAt);

    /**
     * 释放指定账号当前占用的代理回空闲池。
     *
     * <p>上线入口会先释放旧绑定再重新分配;下线/删除等明确不再占用场景也可复用本方法。</p>
     */
    int releaseByAccount(@Param("accountId") Long accountId,
                          @Param("idleStatus") int idleStatus,
                          @Param("usingStatus") int usingStatus,
                          @Param("updatedAt") long updatedAt);

    /**
     * 批量释放指定账号当前占用的代理回空闲池。
     */
    int releaseByAccounts(@Param("accountIds") List<Long> accountIds,
                          @Param("idleStatus") int idleStatus,
                          @Param("usingStatus") int usingStatus,
                          @Param("updatedAt") long updatedAt);

    /**
     * 精确释放账号上线本次分配的代理。
     *
     * <p>补偿释放必须同时匹配账号和代理 ID,避免旧上线请求失败时误释放该账号后续新绑定的代理。</p>
     */
    int releaseOnlineAllocation(@Param("accountId") Long accountId,
                                @Param("proxyId") Long proxyId,
                                @Param("idleStatus") int idleStatus,
                                @Param("usingStatus") int usingStatus,
                                @Param("updatedAt") long updatedAt);

    /**
     * 批量精确释放账号上线本次分配的代理。
     */
    int releaseOnlineAllocations(@Param("targets") List<IpProxyBindTarget> targets,
                                 @Param("idleStatus") int idleStatus,
                                 @Param("usingStatus") int usingStatus,
                                 @Param("updatedAt") long updatedAt);

    /**
     * 按完整身份（网关, 端口, 用户名, 密码）统计活跃行数，用于导入时给友好「跳过重复」提示。
     * 库层另有 uq_active_dedup 唯一键兜底。
     */
    long countActiveByFullTuple(@Param("host") String host, @Param("port") Integer port,
            @Param("username") String username, @Param("password") String password);

    /**
     * 查询指定代理当前绑定账号。
     */
    List<Long> selectBoundAccountIdsByProxyIds(@Param("ids") List<Long> ids, @Param("usingStatus") int usingStatus);

    /**
     * 后台 publisher 按租户显式批量读取活跃代理行。
     *
     * <p>dispatcher 线程不依赖 {@code TenantContext};调用方按 outbox tenant_id 分组后传入租户 ID。
     * 返回行包含 username/password,只能用于拼协议层上线 payload,不得写日志。</p>
     */
    @InterceptorIgnore(tenantLine = "true")
    List<IpProxy> selectActiveByTenantAndIds(@Param("tenantId") Long tenantId, @Param("ids") List<Long> ids);

    /** 批量软删除。 */
    int softDeleteByIds(@Param("ids") List<Long> ids, @Param("deletedAt") long deletedAt);
}
