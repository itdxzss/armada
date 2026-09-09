package com.armada.account.contact.mapper;
import com.armada.account.contact.model.entity.AccountStatusAudience;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
/** 云端完整快照及抓取租约。所有语句受租户插件约束。 */
@Mapper
public interface AccountStatusAudienceMapper {
    AccountStatusAudience selectByAccountId(@Param("accountId") Long accountId);
    List<AccountStatusAudience> selectSummaries(@Param("accountIds") List<Long> accountIds);
    int ensure(AccountStatusAudience row);
    int claim(@Param("row") AccountStatusAudience row, @Param("expectedUpdatedAt") Long expectedUpdatedAt);
    int finish(AccountStatusAudience row);
}
