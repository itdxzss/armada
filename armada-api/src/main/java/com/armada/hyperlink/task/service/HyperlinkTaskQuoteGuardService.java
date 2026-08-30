package com.armada.hyperlink.task.service;

import com.armada.hyperlink.task.model.dto.HyperlinkTaskQuoteDTO;
import com.armada.hyperlink.task.model.dto.HyperlinkTaskSaveDTO;
import com.armada.hyperlink.task.model.entity.HyperlinkTask;
import com.armada.hyperlink.task.model.vo.HyperlinkTaskQuoteVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.AuthPrincipal;
import org.springframework.stereotype.Service;

/** 报价生成、签名验证与任务冻结配置匹配门禁。 */
@Service
public class HyperlinkTaskQuoteGuardService {
    private final HyperlinkTaskQuoteService quoteService;
    private final HyperlinkQuoteTokenService quoteTokenService;

    public HyperlinkTaskQuoteGuardService(HyperlinkTaskQuoteService quoteService,
            HyperlinkQuoteTokenService quoteTokenService) {
        this.quoteService = quoteService;
        this.quoteTokenService = quoteTokenService;
    }

    public HyperlinkQuoteTokenService.QuoteClaims forCreate(HyperlinkTaskSaveDTO request,
            AuthPrincipal principal, long now) {
        String token = request.quoteToken();
        if (request.sourceTaskId() != null) {
            token = internalQuote(request, principal).quoteToken();
        }
        HyperlinkQuoteTokenService.QuoteClaims claims = quoteTokenService.verify(token,
                principal.tenantId(), principal.userId(), "CREATE", null, null, now);
        requireMatchingSave(claims, request);
        if (claims.quote().availableBalance().compareTo(claims.quote().estimatedAmount()) < 0) {
            throw new BusinessException(ErrorCode.HYPERLINK_BALANCE_INSUFFICIENT);
        }
        return claims;
    }

    public HyperlinkQuoteTokenService.QuoteClaims internalForSave(HyperlinkTaskSaveDTO request,
            AuthPrincipal principal) {
        HyperlinkQuoteTokenService.QuoteClaims claims = quoteService.quoteInternal(
                new HyperlinkTaskQuoteService.InternalQuoteInput(principal.tenantId(),
                        principal.userId(), request.dataPackageId(), request.taskMode(),
                        request.maxExecutingAccounts(), request.maxUseAccounts()));
        requireMatchingSave(claims, request);
        return claims;
    }

    public HyperlinkQuoteTokenService.QuoteClaims forStart(String token, long taskId,
            int version, HyperlinkTask task, AuthPrincipal principal, long now) {
        HyperlinkQuoteTokenService.QuoteClaims claims = quoteTokenService.verify(token,
                principal.tenantId(), principal.userId(), "START", taskId, version, now);
        if (claims.dataPackageId() != task.getDataPackageId()
                || claims.maxExecutingAccounts() != task.getConcurrentNum()
                || claims.maxUseAccounts() != task.getMaxUseAccount()
                || !claims.taskMode().equals(modeApi(task.getTaskType()))) {
            throw new BusinessException(ErrorCode.HYPERLINK_QUOTE_STALE, "任务已编辑，请重新报价");
        }
        return claims;
    }

    public HyperlinkQuoteTokenService.QuoteClaims internalForTask(HyperlinkTask task) {
        return quoteService.quoteInternal(new HyperlinkTaskQuoteService.InternalQuoteInput(
                task.getTenantId(), task.getCreatedBy(), task.getDataPackageId(),
                modeApi(task.getTaskType()), task.getConcurrentNum(), task.getMaxUseAccount()));
    }

    private HyperlinkTaskQuoteVO internalQuote(HyperlinkTaskSaveDTO request, AuthPrincipal principal) {
        return quoteService.quote(new HyperlinkTaskQuoteDTO("CREATE", null,
                request.dataPackageId(), request.taskMode(), request.maxExecutingAccounts(),
                request.maxUseAccounts()), principal);
    }

    private void requireMatchingSave(HyperlinkQuoteTokenService.QuoteClaims claims,
            HyperlinkTaskSaveDTO request) {
        if (claims.dataPackageId() != request.dataPackageId()
                || !claims.taskMode().equals(request.taskMode())
                || claims.maxExecutingAccounts() != request.maxExecutingAccounts()
                || claims.maxUseAccounts() != request.maxUseAccounts()) {
            throw new BusinessException(ErrorCode.HYPERLINK_QUOTE_STALE, "任务配置与报价不一致");
        }
    }

    private String modeApi(int code) {
        return switch (code) {
            case 1 -> "instant";
            case 2 -> "rolling";
            case 3 -> "cycle";
            default -> throw new BusinessException(ErrorCode.VALIDATION, "任务模式非法");
        };
    }
}
