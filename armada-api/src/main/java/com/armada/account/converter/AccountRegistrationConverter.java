package com.armada.account.converter;

import com.armada.account.model.entity.AccountRegistrationItem;
import com.armada.account.model.entity.AccountRegistrationTask;
import com.armada.account.model.enums.AccountRegistrationState;
import com.armada.account.model.vo.AccountRegistrationCountsVO;
import com.armada.account.model.vo.AccountRegistrationItemVO;
import com.armada.account.model.vo.AccountRegistrationTaskVO;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

/** 持久化注册数据到租户响应的白名单转换。 */
@Mapper(componentModel = "spring")
public interface AccountRegistrationConverter {
    /** 合并任务定义和实时数据库计数，不创建重复状态源。 */
    AccountRegistrationTaskVO task(AccountRegistrationTask task, AccountRegistrationCountsVO counts, String status);
    /** 不导出租约令牌，状态转为稳定英文枚举名。 */
    @Mapping(target = "state", source = "state", qualifiedByName = "registrationState")
    AccountRegistrationItemVO item(AccountRegistrationItem item);
    /** 转换已受当前租户约束的任务明细。 */
    List<AccountRegistrationItemVO> items(List<AccountRegistrationItem> items);
    /** @param state 持久化状态值 @return API 状态名 */
    @Named("registrationState")
    default String state(Integer state) { return AccountRegistrationState.fromCode(state).name(); }
}
