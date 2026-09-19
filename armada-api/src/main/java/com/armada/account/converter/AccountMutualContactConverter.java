package com.armada.account.converter;

import com.armada.account.model.entity.AccountMutualContactItem;
import com.armada.account.model.entity.AccountMutualContactTask;
import com.armada.account.model.vo.AccountMutualContactItemVO;
import com.armada.account.model.vo.AccountMutualContactStatsVO;
import com.armada.account.model.vo.AccountMutualContactTaskVO;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** 互存任务持久化事实到运营页面出参的编译期转换。 */
@Mapper(componentModel = "spring")
public interface AccountMutualContactConverter {
    /** 任务与查询聚合统计合成出参。 */
    @Mapping(target = "stats", source = "stats")
    AccountMutualContactTaskVO task(AccountMutualContactTask task, AccountMutualContactStatsVO stats);

    /** 定向操作出参不暴露内部协议句柄和命令标识。 */
    AccountMutualContactItemVO item(AccountMutualContactItem item);

    /** 转换 SQL 已完成分页的结果集。 */
    List<AccountMutualContactItemVO> items(List<AccountMutualContactItem> items);
}
