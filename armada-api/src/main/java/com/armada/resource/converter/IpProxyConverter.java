package com.armada.resource.converter;

import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.ProxyOwnership;
import com.armada.resource.model.ProxyProtocol;
import com.armada.resource.model.entity.IpProxy;
import com.armada.resource.model.vo.IpProxyVO;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * IP 代理 entity → VO 转换（MapStruct，编译期生成）。
 *
 * <p>tinyint 码 → 展示名由枚举 {@code labelOf} 算好随出参带出；{@code proxyAddress}=host:port；
 * {@code createdAt} 为 epoch 毫秒直映。</p>
 */
@Mapper(componentModel = "spring",
        imports = {IpProxyStatus.class, ProxyProtocol.class, ProxyOwnership.class})
public interface IpProxyConverter {

    @Mapping(target = "proxyAddress", expression = "java(entity.getHost() + \":\" + entity.getPort())")
    @Mapping(target = "protocolLabel", expression = "java(ProxyProtocol.labelOf(entity.getProtocol()))")
    @Mapping(target = "statusLabel", expression = "java(IpProxyStatus.labelOf(entity.getStatus()))")
    @Mapping(target = "ownershipLabel", expression = "java(ProxyOwnership.labelOf(entity.getOwnership()))")
    @Mapping(target = "validAccountCount", expression = "java(entity.getBoundAccountId() == null ? 0 : 1)")
    IpProxyVO toVO(IpProxy entity);

    List<IpProxyVO> toVOList(List<IpProxy> entities);
}
