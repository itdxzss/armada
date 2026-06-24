package com.armada.resource.converter;

import com.armada.resource.model.IpProxyStatus;
import com.armada.resource.model.ProxyOwnership;
import com.armada.resource.model.ProxyProtocol;
import com.armada.resource.model.entity.IpProxy;
import com.armada.resource.model.vo.IpProxyVO;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * IP 代理 entity → VO 转换（MapStruct，编译期生成）。
 *
 * <p>tinyint 码 → 中文 label 由枚举 {@code labelOf} 算好随出参带出；{@code proxyAddress}=host:port；
 * {@code password} 脱敏；{@code createdAt} DATETIME → epoch 毫秒。</p>
 */
@Mapper(componentModel = "spring",
        imports = {IpProxyStatus.class, ProxyProtocol.class, ProxyOwnership.class})
public interface IpProxyConverter {

    @Mapping(target = "proxyAddress", expression = "java(entity.getHost() + \":\" + entity.getPort())")
    @Mapping(target = "protocolLabel", expression = "java(ProxyProtocol.labelOf(entity.getProtocol()))")
    @Mapping(target = "statusLabel", expression = "java(IpProxyStatus.labelOf(entity.getStatus()))")
    @Mapping(target = "ownershipLabel", expression = "java(ProxyOwnership.labelOf(entity.getOwnership()))")
    @Mapping(target = "password", expression = "java(mask(entity.getPassword()))")
    IpProxyVO toVO(IpProxy entity);

    List<IpProxyVO> toVOList(List<IpProxy> entities);

    /**
     * {@code LocalDateTime} → epoch 毫秒（供 createdAt 字段），MapStruct 按类型自动选用。
     *
     * <p>库内 DATETIME 配 {@code serverTimezone=UTC} 存 UTC 墙钟，须按 {@code ZoneOffset.UTC} 解释才得真实时刻。</p>
     */
    default Long toEpochMilli(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    /** 密码脱敏（出参用，不回传真实凭据）。 */
    default String mask(String password) {
        return password == null || password.isBlank() ? "" : "******";
    }
}
