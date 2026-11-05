package com.armada.account.converter;

import com.armada.account.model.entity.SmsActivation;
import com.armada.account.model.enums.SmsActivationState;
import com.armada.account.model.vo.SmsActivationVO;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/** 手动接码实体到租户响应的转换。 */
@Mapper(componentModel = "spring")
public interface SmsActivationConverter {
    /** 状态转为稳定英文枚举名。 */
    @Mapping(target = "state", source = "state", qualifiedByName = "smsActivationState")
    SmsActivationVO vo(SmsActivation row);
    /** 转换已受当前租户约束的记录。 */
    List<SmsActivationVO> vos(List<SmsActivation> rows);
    /** @param state 持久化状态值 @return API 状态名 */
    @Named("smsActivationState")
    default String state(Integer state) { return SmsActivationState.fromCode(state).name(); }
}
