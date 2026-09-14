package com.armada.account.service;

import com.armada.account.model.vo.AccountOnlineAttemptLogVO;
import java.util.List;

public interface AccountOnlineAttemptLogService {

    void applyOfflineDiagnosed(AccountOfflineDiagnosedEvent event);

    List<AccountOnlineAttemptLogVO> recentByAccount(Long accountId, int limit);

    List<AccountOnlineAttemptLogVO> timeline(String onlineAttemptId, int limit);

    String latestAttemptId(Long accountId);

    /** 与已接受的状态事件同事务保存补偿上下文，不依赖协议额外发送诊断事件。 */
    void recordProxyFailure(AccountStateChangedEvent event, long occurredAt);

    /** 只读取当前失败时间水位的上下文；旧事件可没有 proxyId。 */
    AccountProxyFailureContext proxyFailureAt(Long accountId, Long occurredAt);
}
