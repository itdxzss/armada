package com.armada.account.converter;

import com.armada.account.model.entity.AccountMutualChatItem;
import com.armada.account.model.entity.AccountMutualChatTask;
import com.armada.account.model.vo.AccountMutualChatItemVO;
import com.armada.account.model.vo.AccountMutualChatStatsVO;
import com.armada.account.model.vo.AccountMutualChatTaskVO;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** 互聊任务持久化事实到运营页面出参的编译期转换。 */
@Mapper(componentModel = "spring")
public interface AccountMutualChatConverter {
    /** 任务与查询聚合统计合成出参。 */
    @Mapping(target = "stats", source = "stats")
    AccountMutualChatTaskVO task(AccountMutualChatTask task, AccountMutualChatStatsVO stats);

    /** 定向操作出参不暴露内部协议句柄和命令标识。 */
    AccountMutualChatItemVO item(AccountMutualChatItem item);

    /** 转换 SQL 已完成分页的结果集。 */
    List<AccountMutualChatItemVO> items(List<AccountMutualChatItem> items);
}
