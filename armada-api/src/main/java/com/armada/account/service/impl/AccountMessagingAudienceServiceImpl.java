package com.armada.account.service.impl;

import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.selection.mapper.AccountFilterSelectionMapper;
import com.armada.account.selection.model.SelectedAccount;
import com.armada.account.service.AccountMessagingAudienceService;
import java.util.List;
import org.springframework.stereotype.Service;

/** 通过账号域 Mapper 提供消息任务所需的发送账号与动态受众快照。 */
@Service
public class AccountMessagingAudienceServiceImpl implements AccountMessagingAudienceService {

    private final AccountFilterSelectionMapper selectionMapper;
    private final AccountContactMapper contactMapper;

    public AccountMessagingAudienceServiceImpl(
            AccountFilterSelectionMapper selectionMapper,
            AccountContactMapper contactMapper) {
        this.selectionMapper = selectionMapper;
        this.contactMapper = contactMapper;
    }

    @Override
    public List<SelectedAccount> selectSendableByIds(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return List.of();
        }
        return List.copyOf(selectionMapper.selectSendableByIds(
                accountIds,
                AccountFilterSelectionMapper.ACCOUNT_STATE_NORMAL,
                AccountFilterSelectionMapper.ACCOUNT_STATE_EXPORTED));
    }

    @Override
    public List<String> selectStatusAudienceJids(Long accountId, int limit) {
        if (accountId == null || limit < 1) {
            return List.of();
        }
        return contactMapper.selectNamedByAccount(accountId, limit).stream()
                .map(contact -> contact.getContactJid())
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
