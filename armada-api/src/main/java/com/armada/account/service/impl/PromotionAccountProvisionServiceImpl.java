package com.armada.account.service.impl;

import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.entity.ImportFormat;
import com.armada.account.service.PromotionAccountProvisionCommand;
import com.armada.account.service.PromotionAccountProvisionService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScopeAccess;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 推广配对账号落库实现。
 *
 * <p>复用既有账号三表，不改变导入链路。配对完成即为在线账号，因此默认状态行插入后，
 * 再通过现有状态更新方法收敛为“正常 + 在线”。</p>
 */
@Service
public class PromotionAccountProvisionServiceImpl implements PromotionAccountProvisionService {

    private static final Logger log = LoggerFactory.getLogger(PromotionAccountProvisionServiceImpl.class);
    private static final int NUMBER_SOURCE_PAID_ACQUISITION = 1;
    private static final int OWNERSHIP_SELF = 1;
    private static final int DEFAULT_PRIORITY = 0;
    private static final String STATE_SOURCE = "PROMOTION_PAIRING";

    private final AccountMapper accountMapper;
    private final AccountStateMapper stateMapper;
    private final AccountCredentialMapper credentialMapper;

    public PromotionAccountProvisionServiceImpl(AccountMapper accountMapper,
                                                AccountStateMapper stateMapper,
                                                AccountCredentialMapper credentialMapper) {
        this.accountMapper = accountMapper;
        this.stateMapper = stateMapper;
        this.credentialMapper = credentialMapper;
    }

    @Override
    public boolean existsActiveByPhone(String phone) {
        return StringUtils.hasText(phone) && accountMapper.selectActiveByWsPhone(phone.trim()) != null;
    }

    @Override
    public boolean existsActiveByPhoneGlobally(String phone) {
        return StringUtils.hasText(phone) && accountMapper.existsActiveByWsPhoneAnyTenant(phone.trim());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long provision(PromotionAccountProvisionCommand command) {
        validate(command);
        DataScopeAccess.requireOwnedByActorForCreate(
                DataScopeAccess.requireCurrent(),
                List.of(command.ownerUserId()),
                "推广配对账号");
        if (accountMapper.selectActiveByWsPhone(command.phone()) != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "该 WhatsApp 账号已存在");
        }

        // ① 复用账号身份主表，并记录推广渠道归因；不创建第二套账号表。
        Account account = new Account();
        account.setWsPhone(command.phone());
        account.setAccountType(command.accountType());
        account.setNumberSource(NUMBER_SOURCE_PAID_ACQUISITION);
        account.setChannelName(command.channelName());
        account.setPromotionChannelId(command.promotionChannelId());
        account.setOwnership(OWNERSHIP_SELF);
        account.setProtocolId(ProtocolBackend.WEB.name());
        account.setProtocolAccountId(command.protocolAccountId());
        account.setProtocolAddress(command.protocolAddress());
        account.setPriority(DEFAULT_PRIORITY);
        account.setCreatedAt(command.occurredAt());
        account.setUpdatedAt(command.occurredAt());
        account.setOwnerUserId(command.ownerUserId());
        account.setCreatedBy(command.ownerUserId());
        requireOne(accountMapper.insertPromotionAccount(account), "账号主表写入失败");

        // ② 先插入标准状态行，再使用现成更新方法写入“正常 + 在线”。
        AccountState state = new AccountState();
        state.setAccountId(account.getId());
        state.setProxyFailureCount(0);
        state.setPullIntoGroupCount(0);
        state.setCreatedAt(command.occurredAt());
        state.setUpdatedAt(command.occurredAt());
        requireOne(stateMapper.insert(state), "账号状态初始化失败");

        state.setAccountState(AccountStateCode.NORMAL);
        state.setLoginState(AccountLoginStateCode.ONLINE);
        state.setLastStateSyncTime(command.occurredAt());
        state.setStateSource(STATE_SOURCE);
        requireOne(stateMapper.updateLoginAndAccountState(state), "账号在线状态写入失败");

        if (StringUtils.hasText(command.proxyCountry()) || StringUtils.hasText(command.proxySource())) {
            state.setProxyCountry(command.proxyCountry());
            state.setProxySource(command.proxySource());
            requireOne(stateMapper.updateProxySnapshots(List.of(state)), "账号代理快照写入失败");
        }

        // ③ 完整 Baileys creds + keys 只进入凭据表，日志绝不输出明文。
        AccountCredential credential = new AccountCredential();
        credential.setAccountId(account.getId());
        credential.setWsPhone(command.phone());
        credential.setCredFormat(ImportFormat.JSON.getCode());
        credential.setCredsJson(command.credentialJson());
        credential.setProxySessionId(command.proxySessionId());
        credential.setCreatedAt(command.occurredAt());
        credential.setUpdatedAt(command.occurredAt());
        requireOne(credentialMapper.insertPromotionCredential(credential), "账号凭据写入失败");

        log.info("推广配对账号落库成功 maskPhone={} accountId={} channelId={} credsLen={}",
                maskPhone(command.phone()), account.getId(), command.promotionChannelId(),
                command.credentialJson().length());
        return account.getId();
    }

    private static void validate(PromotionAccountProvisionCommand command) {
        if (command == null || !StringUtils.hasText(command.phone())
                || command.promotionChannelId() == null
                || !StringUtils.hasText(command.protocolAccountId())
                || !StringUtils.hasText(command.credentialJson())
                || command.ownerUserId() == null || command.ownerUserId() <= 0
                || (command.accountType() != 1 && command.accountType() != 2)
                || command.occurredAt() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "配对账号落库参数不完整");
        }
    }

    private static void requireOne(int affected, String message) {
        if (affected != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, message);
        }
    }

    private static String maskPhone(String phone) {
        return phone.length() <= 4 ? "****" : phone.substring(0, phone.length() - 4) + "****";
    }
}
