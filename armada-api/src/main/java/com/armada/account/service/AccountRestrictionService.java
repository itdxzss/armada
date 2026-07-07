package com.armada.account.service;

public interface AccountRestrictionService {

    void markGroupCreateRestricted(Long accountId, String protocolAccountId, String reason, long occurredAt);
}
