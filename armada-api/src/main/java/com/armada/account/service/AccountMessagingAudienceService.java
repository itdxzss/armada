package com.armada.account.service;

import com.armada.account.selection.model.SelectedAccount;
import com.armada.account.contact.model.StatusAudienceView;
import com.armada.account.contact.model.StatusAudienceResolution;
import java.util.Map;
import java.util.List;

/** 账号域向消息任务提供的发送前协议事实与动态可见联系人边界。 */
public interface AccountMessagingAudienceService {

    /** 按账号 ID 复查当前租户内仍可发送的协议事实。 */
    List<SelectedAccount> selectSendableByIds(List<Long> accountIds);

    /** 查询一个账号用于 Status 广播的具名联系人 JID。 */
    List<String> selectStatusAudienceJids(Long accountId, int limit);
    /** 解析受众，必要时异步准备 Android 云端快照。 */
    StatusAudienceResolution resolveStatusAudience(SelectedAccount account, int limit);
    /** 读取当前页准备状态，无协议副作用。 */
    Map<Long, StatusAudienceView> statusAudienceViews(List<Long> accountIds);
    /** 为当前租户可发送账号重新准备受众，不重发消息。 */
    StatusAudienceView refreshStatusAudience(Long accountId);

}
