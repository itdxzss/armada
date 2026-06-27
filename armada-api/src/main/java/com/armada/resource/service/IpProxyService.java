package com.armada.resource.service;

import com.armada.resource.model.dto.IpProxyImportDTO;
import com.armada.resource.model.dto.IpProxyQuery;
import com.armada.resource.model.vo.IpProxyImportResultVO;
import com.armada.resource.model.vo.IpProxyVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
import java.util.List;

/**
 * IP 代理池业务接口。承载「IP 管理」菜单的列表、批量导入、批量删除能力。
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
     * 为账号上线分配一条空闲代理。
     *
     * <p>本方法是 resource 域暴露给 account 域的跨域边界:account 域只能拿到
     * {@link IpProxyAllocation} 结果,看不到 resource mapper/entity。方法内部短事务会先释放该账号旧绑定,
     * 再 {@code SELECT ... FOR UPDATE} 锁定一条本租户 IDLE 代理并置为 IN_USE。</p>
     *
     * @param accountId 账号主键
     * @return 协议上线编排可使用的代理分配结果
     * @throws BusinessException 当账号 ID 为空、缺少租户上下文、没有空闲代理或分配冲突时抛出
     */
    IpProxyAllocation allocateOnlineEndpoint(Long accountId);

    /**
     * 为一批账号上线分配空闲代理。
     *
     * <p>本方法在一个本地短事务中完成:批量释放这些账号旧绑定、锁定同等数量 IDLE 代理、
     * 再批量绑定为 IN_USE。事务不包含协议 HTTP 调用,只保护本地代理池占用关系。</p>
     *
     * @param accountIds 账号主键列表,不可为空、不可重复
     * @return 与 accountIds 顺序一致的代理分配结果
     * @throws BusinessException 当账号列表为空、缺少租户上下文、空闲代理不足或分配冲突时抛出
     */
    List<IpProxyAccountAllocation> allocateOnlineEndpoints(List<Long> accountIds);

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
     * 批量软删除 IP 代理。空列表直接返回、不做任何操作。
     *
     * @param ids 要删除的代理 ID 列表
     */
    void batchDelete(List<Long> ids);
}
