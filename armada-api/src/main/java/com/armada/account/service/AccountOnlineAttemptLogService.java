package com.armada.account.service;

import com.armada.account.model.vo.AccountOnlineAttemptLogVO;
import java.util.List;

public interface AccountOnlineAttemptLogService {

    void applyOfflineDiagnosed(AccountOfflineDiagnosedEvent event);

    List<AccountOnlineAttemptLogVO> recentByAccount(Long accountId, int limit);

    List<AccountOnlineAttemptLogVO> timeline(String onlineAttemptId, int limit);

    String latestAttemptId(Long accountId);

    /** 返回最近一次带代理 ID 的 PROXY_FAILED 诊断；没有诊断时返回 null。 */
    AccountProxyFailureContext latestProxyFailure(Long accountId);
}
