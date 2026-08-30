package com.armada.account.service.impl;

import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.enums.AccountTypeCode;
import com.armada.account.model.enums.AccountTypeVerifySourceCode;
import com.armada.account.model.enums.AccountTypeVerifyStatusCode;
import com.armada.account.model.enums.BusinessVerificationLevelCode;
import com.armada.account.service.AccountTypeDetectedEvent;
import com.armada.account.service.AccountTypeVerificationService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 账号类型协议检测结果的校验与幂等落库实现。 */
@Service
public class AccountTypeVerificationServiceImpl implements AccountTypeVerificationService {

    private static final Logger log = LoggerFactory.getLogger(AccountTypeVerificationServiceImpl.class);

    private final AccountMapper accountMapper;
    private final AccountCredentialMapper credentialMapper;

    /**
     * 创建账号类型校验服务。
     *
     * @param accountMapper 账号身份数据访问
     * @param credentialMapper 当前凭据数据访问
     */
    public AccountTypeVerificationServiceImpl(AccountMapper accountMapper,
                                              AccountCredentialMapper credentialMapper) {
        this.accountMapper = accountMapper;
        this.credentialMapper = credentialMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean applyDetected(AccountTypeDetectedEvent event) {
        validate(event);
        Account account = accountMapper.selectActiveById(event.accountId());
        if (account == null || !event.protocolAccountId().equals(account.getProtocolAccountId())) {
            log.info("账号类型检测忽略,账号不存在或协议身份已变化 tenantId={} accountId={} eventId={}",
                    event.tenantId(), event.accountId(), event.eventId());
            return false;
        }
        AccountCredential credential = credentialMapper.selectByAccountId(event.accountId());
        if (credential == null || !event.credentialVersion().equals(credential.getUpdatedAt())) {
            log.info("账号类型检测忽略,凭据版本已变化 tenantId={} accountId={} eventVersion={} eventId={}",
                    event.tenantId(), event.accountId(), event.credentialVersion(), event.eventId());
            return false;
        }

        Integer detectedType = detectedType(event.detectedAccountType());
        Integer sourceCode = sourceCode(event.source());
        Integer verificationLevel = verificationLevel(event.verificationLevel());
        if (detectedType != null && sourceCode == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号类型检测来源不可信");
        }
        int currentType = account.getAccountType();
        int declaredType = account.getDeclaredAccountType() == null
                ? currentType : account.getDeclaredAccountType();
        int status = detectedType == null
                ? AccountTypeVerifyStatusCode.INCONCLUSIVE
                : detectedType == declaredType
                ? AccountTypeVerifyStatusCode.MATCHED
                : AccountTypeVerifyStatusCode.CORRECTED;

        Account update = new Account();
        update.setTenantId(event.tenantId());
        update.setId(event.accountId());
        update.setProtocolAccountId(event.protocolAccountId());
        update.setProtocolId(event.protocolBackend().trim().toUpperCase(Locale.ROOT));
        // UNKNOWN 不携带类型事实，SQL 必须完全跳过 account_type 赋值，避免并发时把
        // 另一条可靠检测已经纠正的类型覆盖回本次查询前读取的旧值。
        update.setAccountType(detectedType);
        update.setAccountTypeVerifyStatus(status);
        update.setAccountTypeVerifySource(sourceCode);
        update.setAccountTypeVerifiedAt(event.detectedAt());
        update.setBusinessVerificationLevel(verificationLevel);
        if (verificationLevel != null) {
            update.setBusinessVerificationSource(sourceCode);
            update.setBusinessVerificationVerifiedAt(event.detectedAt());
        }
        update.setUpdatedAt(System.currentTimeMillis());
        boolean applied = accountMapper.updateTypeVerification(update, event.credentialVersion()) == 1;
        if (applied) {
            log.info("账号类型检测已应用 tenantId={} accountId={} oldType={} declaredType={} newType={} status={} verificationLevel={} source={} eventId={}",
                    event.tenantId(), event.accountId(), currentType, declaredType,
                    detectedType == null ? currentType : detectedType,
                    status, event.verificationLevel(), event.source(), event.eventId());
        }
        return applied;
    }

    private static void validate(AccountTypeDetectedEvent event) {
        if (event == null || event.tenantId() == null || event.tenantId() <= 0
                || event.accountId() == null || event.accountId() <= 0
                || event.protocolAccountId() == null || event.protocolAccountId().isBlank()
                || event.protocolBackend() == null
                || !("WEB".equalsIgnoreCase(event.protocolBackend().trim())
                || "ANDROID".equalsIgnoreCase(event.protocolBackend().trim()))
                || event.credentialVersion() == null || event.credentialVersion() <= 0
                || event.detectedAt() == null || event.detectedAt() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号类型检测事件参数不完整");
        }
    }

    private static Integer detectedType(String value) {
        if (value == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号类型检测结果为空");
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "PERSONAL" -> AccountTypeCode.PERSONAL;
            case "BUSINESS_STANDARD", "BUSINESS_VERIFIED" -> AccountTypeCode.BUSINESS;
            case "UNKNOWN" -> null;
            default -> throw new BusinessException(ErrorCode.VALIDATION, "账号类型检测结果非法");
        };
    }

    private static Integer sourceCode(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "creds_meta" -> AccountTypeVerifySourceCode.CREDS_META;
            case "pair_success" -> AccountTypeVerifySourceCode.PAIR_SUCCESS;
            case "business_profile_query" -> AccountTypeVerifySourceCode.BUSINESS_PROFILE_QUERY;
            case "vip_hint", "unknown" -> null;
            default -> throw new BusinessException(ErrorCode.VALIDATION, "账号类型检测来源非法");
        };
    }

    private static Integer verificationLevel(String value) {
        if (value == null || value.isBlank() || "UNKNOWN".equalsIgnoreCase(value.trim())) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "HIGH" -> BusinessVerificationLevelCode.HIGH;
            case "NOT_HIGH" -> BusinessVerificationLevelCode.NOT_HIGH;
            default -> throw new BusinessException(ErrorCode.VALIDATION, "商业认证级别非法");
        };
    }
}
