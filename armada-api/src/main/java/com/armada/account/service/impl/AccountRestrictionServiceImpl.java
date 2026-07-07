package com.armada.account.service.impl;

import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.service.AccountRestrictionService;
import com.armada.platform.protocol.model.command.ProtocolOfflineCommandRequest;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AccountRestrictionServiceImpl implements AccountRestrictionService {

    private static final String STATE_SOURCE_GROUP_CREATE_RESTRICTED = "GROUP_CREATE_RESTRICTED";
    private static final String OFFLINE_SOURCE_GROUP_CREATE_RESTRICTED = "group_create_restricted";
    private static final String DEFAULT_REASON = "account_reachout_restricted";
    private static final int BLOCK_REASON_MAX_LENGTH = 255;

    private final AccountStateMapper stateMapper;
    private final ProtocolCommandOutboxService outboxService;

    public AccountRestrictionServiceImpl(AccountStateMapper stateMapper,
                                         ProtocolCommandOutboxService outboxService) {
        this.stateMapper = stateMapper;
        this.outboxService = outboxService;
    }

    @Override
    public void markGroupCreateRestricted(Long accountId, String protocolAccountId, String reason, long occurredAt) {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId must not be null");
        }
        long updatedAt = System.currentTimeMillis();
        String blockReason = clamp(StringUtils.hasText(reason) ? reason : DEFAULT_REASON, BLOCK_REASON_MAX_LENGTH);
        AccountState row = new AccountState();
        row.setAccountId(accountId);
        row.setLoginState(AccountLoginStateCode.OFFLINE);
        row.setAccountState(AccountStateCode.RESTRICTED);
        row.setStateSource(STATE_SOURCE_GROUP_CREATE_RESTRICTED);
        row.setBlockReason(blockReason);
        row.setLastStateSyncTime(occurredAt);
        row.setUpdatedAt(updatedAt);

        stateMapper.updateLifecycleState(row);
        stateMapper.updateBlockReason(row);
        if (StringUtils.hasText(protocolAccountId)) {
            outboxService.enqueueOfflineCommands(List.of(new ProtocolOfflineCommandRequest(
                    accountId,
                    protocolAccountId,
                    OFFLINE_SOURCE_GROUP_CREATE_RESTRICTED)));
        }
    }

    private static String clamp(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
