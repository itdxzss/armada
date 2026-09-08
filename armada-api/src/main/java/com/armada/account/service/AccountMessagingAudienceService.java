package com.armada.account.service;

import com.armada.account.selection.model.SelectedAccount;
import java.util.List;

/** 账号域向消息任务提供的发送前协议事实与动态可见联系人边界。 */
public interface AccountMessagingAudienceService {

    /** 按账号 ID 复查当前租户内仍可发送的协议事实。 */
    List<SelectedAccount> selectSendableByIds(List<Long> accountIds);

    /** 查询一个账号用于 Status 广播的具名联系人 JID。 */
    List<String> selectStatusAudienceJids(Long accountId, int limit);
}
