package com.armada.account.service.impl;

import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.selection.mapper.AccountFilterSelectionMapper;
import com.armada.account.selection.model.SelectedAccount;
import com.armada.account.service.AccountMessagingAudienceService;
import com.armada.account.contact.model.StatusAudienceView;
import com.armada.account.contact.model.StatusAudienceResolution;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import com.armada.account.contact.mapper.AccountStatusAudienceMapper;
import com.armada.account.contact.service.CloudStatusAudienceService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

import org.springframework.stereotype.Service;

/** 通过账号域 Mapper 提供消息任务所需的发送账号与动态受众快照。 */
@Service
public class AccountMessagingAudienceServiceImpl implements AccountMessagingAudienceService {

    private final AccountFilterSelectionMapper selectionMapper;
    private final AccountContactMapper contactMapper;
    private final AccountStatusAudienceMapper cloudMapper;
    private final CloudStatusAudienceService cloudService;

    public AccountMessagingAudienceServiceImpl(
            AccountFilterSelectionMapper selectionMapper,
            AccountContactMapper contactMapper,
            AccountStatusAudienceMapper cloudMapper,
            CloudStatusAudienceService cloudService) {
        this.selectionMapper = selectionMapper;
        this.contactMapper = contactMapper;
        this.cloudMapper = cloudMapper;
        this.cloudService = cloudService;
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

    @Override
    public StatusAudienceResolution resolveStatusAudience(SelectedAccount account, int limit) {
        List<String> local = selectStatusAudienceJids(account.accountId(), Math.min(5000, limit) + 1);
        if (!local.isEmpty()) {
            if (local.size() > limit || local.size() > 5000) {
                return new StatusAudienceResolution(new StatusAudienceView("FAILED", "ADDRESS_BOOK", local.size(), null,
                        "STATUS_AUDIENCE_TOO_LARGE", "通讯录受众超出单次上限"), List.of());
            }
            return new StatusAudienceResolution(localView(local.size()), local);
        }
        if (ProtocolBackend.fromProtocolId(account.protocolId()) != ProtocolBackend.ANDROID) {
            return new StatusAudienceResolution(unavailable(), List.of());
        }
        StatusAudienceResolution result = cloudService.resolve(account, false);
        if (result.jids().size() > limit) {
            return new StatusAudienceResolution(new StatusAudienceView("FAILED", "CLOUD_LID", result.jids().size(), null,
                    "STATUS_AUDIENCE_TOO_LARGE", "云端受众超出当前任务上限"), List.of());
        }
        return result;
    }

    @Override
    public StatusAudienceResolution resolveContactAudience(SelectedAccount account, long requestedAfter) {
        if (ProtocolBackend.fromProtocolId(account.protocolId()) != ProtocolBackend.ANDROID) {
            return new StatusAudienceResolution(new StatusAudienceView("FAILED", "CLOUD_LID", 0, null,
                    "CLOUD_BACKEND_UNSUPPORTED", "账号协议不支持云端 LID 名单"), List.of());
        }
        var current = cloudMapper.selectByAccountId(account.accountId());
        var currentView = CloudStatusAudienceService.view(current, System.currentTimeMillis());
        // 每个新私聊任务重新准备名单，避免复用此前尚未排除自身的缓存；本任务结果及正在采集的代次仍复用。
        boolean refresh = ("READY".equals(currentView.status()) || "EMPTY".equals(currentView.status())
                || "FAILED".equals(currentView.status())) && currentView.updatedAt() != null
                && currentView.updatedAt() < requestedAfter;
        return cloudService.resolve(account, refresh);
    }

    @Override
    public Map<Long, StatusAudienceView> statusAudienceViews(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) { return Map.of(); }
        Map<Long, StatusAudienceView> views = new HashMap<>();
        for (Long id : accountIds) { views.put(id, new StatusAudienceView("UNAVAILABLE", "NONE", 0, null,
                "ACCOUNT_NOT_SENDABLE", "账号当前不可发送，无法准备受众")); }
        var cloud = cloudMapper.selectSummaries(accountIds).stream().collect(java.util.stream.Collectors.toMap(
                row -> row.getAccountId(), row -> CloudStatusAudienceService.view(row, System.currentTimeMillis())));
        var sendable = selectSendableByIds(accountIds);
        var sendableIds = sendable.stream().map(SelectedAccount::accountId).collect(java.util.stream.Collectors.toSet());
        for (SelectedAccount fact : sendable) {
            views.put(fact.accountId(), ProtocolBackend.fromProtocolId(fact.protocolId()) == ProtocolBackend.ANDROID
                    ? cloud.getOrDefault(fact.accountId(), CloudStatusAudienceService.view(null, System.currentTimeMillis()))
                    : unavailable());
        }
        for (var count : contactMapper.countNamedByAccounts(accountIds)) {
            if (sendableIds.contains(count.accountId())) {
                views.put(count.accountId(), localView(count.contactNum()));
            }
        }
        return Map.copyOf(views);
    }

    @Override
    public StatusAudienceView refreshStatusAudience(Long accountId) {
        List<SelectedAccount> accounts = selectSendableByIds(List.of(accountId));
        if (accounts.isEmpty()) { throw new BusinessException(ErrorCode.CONFLICT, "账号当前不可发送，请刷新账号状态"); }
        SelectedAccount account = accounts.get(0);
        List<String> local = selectStatusAudienceJids(accountId, 5001);
        if (!local.isEmpty()) { return localView(local.size()); }
        if (ProtocolBackend.fromProtocolId(account.protocolId()) != ProtocolBackend.ANDROID) { return unavailable(); }
        return cloudService.resolve(account, true).view();
    }

    private static StatusAudienceView localView(int count) {
        return new StatusAudienceView(count > 5000 ? "FAILED" : "READY", "ADDRESS_BOOK", count, null,
                count > 5000 ? "STATUS_AUDIENCE_TOO_LARGE" : null, count > 5000 ? "通讯录受众超出单次上限" : null);
    }

    private static StatusAudienceView unavailable() {
        return new StatusAudienceView("UNAVAILABLE", "ADDRESS_BOOK", 0, null, "NO_STATUS_RECIPIENTS",
                "Web 账号没有具名通讯录受众，请先完成通讯录同步");
    }
}
