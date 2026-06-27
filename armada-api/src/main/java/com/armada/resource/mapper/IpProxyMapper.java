package com.armada.resource.mapper;

import com.armada.resource.model.dto.IpProxyQuery;
import com.armada.resource.model.entity.IpProxy;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * IP 代理数据访问。tenant_id 由租户行隔离拦截器自动注入，SQL 不手写 tenant_id 过滤。
 */
@Mapper
public interface IpProxyMapper {

    /** 列表分页查询（SQL 下推 LIMIT/OFFSET）。 */
    List<IpProxy> selectPage(@Param("q") IpProxyQuery query);

    /** 列表总数（与 selectPage 共用筛选片段，口径一致）。 */
    long countPage(@Param("q") IpProxyQuery query);

    /** 插入（不含 tenant_id 列，由拦截器注入）。 */
    int insert(IpProxy entity);

    /**
     * 按 ID 查活跃代理行(deleted_at IS NULL)。
     *
     * <p>用于账号手动上线时把指定 proxyId 转成协议层代理端点;tenant_id 由租户拦截器注入。</p>
     *
     * @param id 代理主键
     * @return 活跃代理行;不存在或已软删时返回 null
     */
    IpProxy selectActiveById(@Param("id") Long id);

    /**
     * 按完整身份（网关, 端口, 用户名, 密码）统计活跃行数，用于导入时给友好「跳过重复」提示。
     * 库层另有 uq_active_dedup 唯一键兜底。
     */
    long countActiveByFullTuple(@Param("host") String host, @Param("port") Integer port,
            @Param("username") String username, @Param("password") String password);

    /** 批量软删除。 */
    int softDeleteByIds(@Param("ids") List<Long> ids, @Param("deletedAt") long deletedAt);
}
