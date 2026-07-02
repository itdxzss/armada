package com.armada.resource.service;

import com.armada.resource.model.dto.IpProxyImportDTO;
import com.armada.resource.model.dto.IpProxyQuery;
import com.armada.resource.model.vo.IpProxyImportResultVO;
import com.armada.resource.model.vo.IpProxyImportSampleCheckVO;
import com.armada.resource.model.vo.IpProxyCheckResultVO;
import com.armada.resource.model.vo.IpProxyVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
import java.util.List;

/**
 * IP 代理池业务接口。承载「IP 管理」菜单的列表、批量导入和账号上线代理分配能力。
 */
public interface IpProxyService {

    /**
     * 分页列出本租户 IP 代理池，供列表页展示。
     *
     * <p>按国家(region)、协议、来源、关键字组合筛选（多条件为且关系），结果按 ID 倒序；分页与总数走 SQL 下推。</p>
     *
     * @param query 搜索与分页条件
     * @return 当前页代理视图及总数
     */
    PageResult<IpProxyVO> list(IpProxyQuery query);

    /**
     * 列出本租户 IP 池中已有的国家/区域。
     *
     * <p>结果由 SQL 去重并过滤空值,「混合（不限国家）」置顶,用于 IP 管理和账号导入页下拉。</p>
     *
     * @return 去重后的国家/区域列表
     */
    List<String> listRegions();

    /**
     * 批量导入 IP 代理到本租户池。
     *
     * <p>{@code text} 逐行解析 {@code host:port:username:password}，格式不合格行跳过并记原因；
     * 按完整身份 (网关,端口,用户名,密码) 去重，命中已有活跃行→跳过。国家/协议/来源为本次统一属性。</p>
     *
     * @param dto 导入参数（国家、协议、来源、多行原文）
     * @return 导入结果（总数/新增/跳过/失败 + 失败原因）
     * @throws BusinessException 协议/来源/内容为空，或协议码非法
     */
    IpProxyImportResultVO importProxies(IpProxyImportDTO dto);

    /**
     * 对 TXT 导入内容做手动抽样检测。
     *
     * <p>该方法服务导入弹框的“检测”按钮:每次从本批实际会新增的候选里随机抽最多 5 条检测。
     * 导入接口本身不校验本结果,前端保存检测通过状态并控制导入按钮。</p>
     *
     * @param dto 导入参数（国家、协议、来源、多行原文）
     * @return 本次抽样检测结果
     * @throws BusinessException 协议/来源/内容为空，或协议码非法
     */
    IpProxyImportSampleCheckVO sampleCheckImport(IpProxyImportDTO dto);

    /**
     * 对单条 IP 代理执行真实出口检测并更新检测字段。
     *
     * @param id ip_proxy 主键
     * @return 本次检测结果
     * @throws BusinessException 当 ID 为空或代理不存在时抛出
     */
    IpProxyCheckResultVO checkProxy(Long id);

    /**
     * 批量执行 IP 代理真实出口检测。
     *
     * @param ids ip_proxy 主键列表,最多 20 个
     * @return 按请求顺序返回的检测结果
     * @throws BusinessException 当列表为空、超过限制或存在空 ID 时抛出
     */
    List<IpProxyCheckResultVO> checkProxies(List<Long> ids);

    /**
     * 重新检测不可用 IP 代理。
     *
     * <p>该方法供后台定时任务调用:跨租户拉取不可用 IP,再按每条 IP 的租户上下文执行真实检测并落库。
     * 检测成功会把代理恢复为空闲;检测失败继续保持不可用,等待下一轮。</p>
     *
     * @param batchSize 本轮最多检测数量;小于等于 0 时跳过
     * @return 本轮重检摘要
     */
    IpProxyRecheckResult recheckUnavailableProxies(int batchSize);

    /**
     * 为账号上线分配一条空闲代理。
     *
     * <p>本方法是 resource 域暴露给 account 域的跨域边界:account 域只能拿到
     * {@link IpProxyAllocation} 结果,看不到 resource mapper/entity。方法内部短事务会先释放该账号旧绑定,
     * 再按「指定国家 → 混合 → 其它国家」优先级锁定一条本租户 IDLE 代理并置为 IN_USE。</p>
     *
     * @param request 账号主键与导入时选择的 IP 国家
     * @return 协议上线编排可使用的代理分配结果
     * @throws BusinessException 当账号 ID 为空、缺少租户上下文、没有空闲代理或分配冲突时抛出
     */
    IpProxyAllocation allocateOnlineEndpoint(IpProxyAllocationRequest request);

    /**
     * 为一批账号上线分配空闲代理。
     *
     * <p>本方法在一个本地短事务中完成:批量释放这些账号旧绑定、按每个账号的国家偏好锁定同等数量 IDLE 代理、
     * 再批量绑定为 IN_USE。事务不包含协议 HTTP 调用,只保护本地代理池占用关系。</p>
     *
     * @param requests 账号分配请求列表,不可为空、账号不可重复
     * @return 与 requests 顺序一致的代理分配结果
     * @throws BusinessException 当账号列表为空、缺少租户上下文、空闲代理不足或分配冲突时抛出
     */
    List<IpProxyAccountAllocation> allocateOnlineEndpoints(List<IpProxyAllocationRequest> requests);

    /**
     * 为一批账号上线分配空闲代理,并排除指定代理 ID。
     *
     * <p>用于删除 IP 前的在线账号重登:被删除的旧 IP 会先被释放,但不能再次被本次分配选中。</p>
     *
     * @param requests         账号分配请求列表,不可为空、账号不可重复
     * @param excludedProxyIds 本次分配禁止选中的代理 ID 列表
     * @return 与 requests 顺序一致的代理分配结果
     * @throws BusinessException 当账号列表为空、代理 ID 为空、缺少租户上下文、空闲代理不足或分配冲突时抛出
     */
    List<IpProxyAccountAllocation> allocateOnlineEndpointsExcludingProxyIds(
            List<IpProxyAllocationRequest> requests,
            List<Long> excludedProxyIds);

    /**
     * 查询指定 IP 代理当前绑定的账号 ID。
     *
     * <p>只返回未软删且处于 IN_USE 的代理绑定。离线/在线由 account 域根据 account_state 再判断。</p>
     *
     * @param ids 代理 ID 列表
     * @return 当前绑定账号 ID 列表;空入参返回空列表
     * @throws BusinessException 当列表中存在空代理 ID 时抛出
     */
    List<Long> findBoundAccountIdsByProxyIds(List<Long> ids);

    /**
     * 释放账号上线过程中本次分配的代理。
     *
     * <p>本方法用于协议层未受理或调用失败后的补偿释放,必须同时按账号 ID 和代理 ID 精确匹配,
     * 避免并发上线时释放掉同账号后续新分配的代理。</p>
     *
     * @param accountId 账号主键
     * @param proxyId   本次分配的代理主键
     * @throws BusinessException 当账号 ID 或代理 ID 为空时抛出
     */
    void releaseOnlineAllocation(Long accountId, Long proxyId);

    /**
     * 批量释放账号上线过程中本次分配的代理。
     *
     * <p>每个释放项同时匹配账号 ID 和代理 ID,避免旧请求失败时误释放同账号后续新分配的代理。</p>
     *
     * @param allocations 需要释放的本次代理分配结果
     * @throws BusinessException 当列表为空或存在空账号/代理 ID 时抛出
     */
    void releaseOnlineAllocations(List<IpProxyAccountAllocation> allocations);

    /**
     * 释放账号当前占用的代理回空闲池。
     *
     * <p>用于协议层确认账号离线后的正常释放。该方法只按账号 ID 释放当前 IN_USE 绑定,
     * 不要求调用方知道具体代理 ID。</p>
     *
     * @param accountId 账号主键
     * @throws BusinessException 当账号 ID 为空时抛出
     */
    void releaseByAccount(Long accountId);

    /**
     * 将账号当前绑定代理标记为不可用并解绑。
     *
     * <p>用于协议层上报 {@code PROXY_FAILED}:旧代理不再回到空闲池,而是等待后台重检恢复。
     * 只处理事件发生时已经绑定的当前代理;没有命中绑定时视为幂等跳过。</p>
     *
     * @param accountId 账号主键
     * @param occurredAt 协议事件发生时间(epoch毫秒)
     * @param reason 上游失败原因
     * @throws BusinessException 当账号 ID 为空时抛出
     */
    void markBoundProxyUnavailableByAccount(Long accountId, long occurredAt, String reason);

}
