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
     * 批量软删除 IP 代理。空列表直接返回、不做任何操作。
     *
     * @param ids 要删除的代理 ID 列表
     */
    void batchDelete(List<Long> ids);
}
