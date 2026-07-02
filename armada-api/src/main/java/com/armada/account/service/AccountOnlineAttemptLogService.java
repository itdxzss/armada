package com.armada.account.service;

import com.armada.account.model.vo.AccountOnlineAttemptLogVO;
import java.util.List;

public interface AccountOnlineAttemptLogService {

    void applyOfflineDiagnosed(AccountOfflineDiagnosedEvent event);

    List<AccountOnlineAttemptLogVO> recentByAccount(Long accountId, int limit);

    List<AccountOnlineAttemptLogVO> timeline(String onlineAttemptId, int limit);

    String latestAttemptId(Long accountId);
}
