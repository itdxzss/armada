package com.armada.account.converter;
import com.armada.account.model.entity.AccountTierCheckItem;
import com.armada.account.model.entity.AccountTierCheckTask;
import com.armada.account.model.vo.AccountTierCheckItemVO;
import com.armada.account.model.vo.AccountTierCheckTaskVO;
import com.armada.account.model.vo.AccountTierCheckStatsVO;
import com.armada.platform.protocol.model.result.ProtocolAccountIntegrity;
import java.util.List;
import java.util.Map;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
/** 本功能编译期映射，不向 API 暴露内部身份和调度字段。 */
@Mapper(componentModel = "spring")
public interface AccountTierCheckConverter {
    /** 组合 SQL 分页批次及聚合统计。 */
    AccountTierCheckTaskVO task(AccountTierCheckTask task, String sourceTaskName,
            Map<String, Long> tierCounts, AccountTierCheckStatsVO stats);
    /** 已由 SQL 分页的明细结果。 */
    List<AccountTierCheckItemVO> items(List<AccountTierCheckItem> items);
    /** 仅成功查询调用；非成功结果不复制任何字段。 */
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "serverUserType", source = "userType")
    @Mapping(target = "trustTier", source = "trustTier")
    @Mapping(target = "isSuspicious", source = "isSuspicious")
    @Mapping(target = "isBanned", source = "isBanned")
    @Mapping(target = "isMvSubscribed", source = "isMvSubscribed")
    @Mapping(target = "joinDateMs", source = "joinDateMs")
    @Mapping(target = "phoneCountryCode", source = "phoneCountryCode")
    void result(ProtocolAccountIntegrity result, @MappingTarget AccountTierCheckItem item);
}
