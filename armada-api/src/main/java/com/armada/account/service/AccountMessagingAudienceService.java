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
    /**
     * 为通讯录私聊任务取得账号自己的完整云端 LID 名单，不依赖具名通讯录或动态隐私。
     * @param account 当前租户内已复查的可发送账号
     * @param requestedAfter 本任务账号创建时间；只允许重新准备早于本任务的历史失败
     * @return 完整名单或显式准备/失败状态，不返回部分结果
     */
    StatusAudienceResolution resolveContactAudience(SelectedAccount account, long requestedAfter);
    /** 读取当前页准备状态，无协议副作用。 */
    Map<Long, StatusAudienceView> statusAudienceViews(List<Long> accountIds);
    /** 为当前租户可发送账号重新准备受众，不重发消息。 */
    StatusAudienceView refreshStatusAudience(Long accountId);

}
