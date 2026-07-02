package com.armada.account.service.impl;

import com.armada.account.mapper.AccountOnlineAttemptLogMapper;
import com.armada.account.model.entity.AccountOnlineAttemptLog;
import com.armada.account.model.vo.AccountOnlineAttemptLogVO;
import com.armada.account.service.AccountOfflineDiagnosedEvent;
import com.armada.account.service.AccountOnlineAttemptLogService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AccountOnlineAttemptLogServiceImpl implements AccountOnlineAttemptLogService {

    private static final int RAW_REASON_MAX_LENGTH = 512;
    private final AccountOnlineAttemptLogMapper mapper;

    public AccountOnlineAttemptLogServiceImpl(AccountOnlineAttemptLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void applyOfflineDiagnosed(AccountOfflineDiagnosedEvent event) {
        AccountOnlineAttemptLog row = new AccountOnlineAttemptLog();
        row.setAccountId(event.accountId());
        row.setProtocolAccountId(event.protocolAccountId());
        row.setOnlineAttemptId(event.onlineAttemptId());
        row.setPreviousOnlineAttemptId(event.previousOnlineAttemptId());
        row.setCommandId(event.commandId());
        row.setBatchId(event.batchId());
        row.setProxyId(event.proxyId());
        row.setSource(event.source());
        row.setFromState(event.from());
        row.setToState(event.to());
        row.setDiagnosisCode(event.diagnosisCode());
        row.setDiagnosisClass(event.diagnosisClass());
        row.setRawCode(event.rawCode());
        row.setRawReason(truncate(event.rawReason(), RAW_REASON_MAX_LENGTH));
        row.setRecoverability(event.recoverability());
        row.setActionTaken(event.actionTaken());
        row.setWorkerId(event.workerId());
        row.setEvidenceJson(event.evidenceJson());
        row.setOccurredAt(epochMillis(event.occurredAt()));
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        mapper.insert(row);
    }

    @Override
    public List<AccountOnlineAttemptLogVO> recentByAccount(Long accountId, int limit) {
        return mapper.selectRecentByAccountId(accountId, normalizeLimit(limit)).stream()
                .map(AccountOnlineAttemptLogVO::from)
                .toList();
    }

    @Override
    public List<AccountOnlineAttemptLogVO> timeline(String onlineAttemptId, int limit) {
        return mapper.selectByAttemptId(onlineAttemptId, normalizeLimit(limit)).stream()
                .map(AccountOnlineAttemptLogVO::from)
                .toList();
    }

    @Override
    public String latestAttemptId(Long accountId) {
        return mapper.selectLatestAttemptIdByAccountId(accountId);
    }

    private static int normalizeLimit(int limit) {
        if (limit <= 0) return 20;
        return Math.min(limit, 200);
    }

    private static LocalDateTime epochMillis(Long value) {
        long millis = value == null ? System.currentTimeMillis() : value;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
}
