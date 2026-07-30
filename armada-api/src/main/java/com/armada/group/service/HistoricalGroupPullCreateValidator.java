package com.armada.group.service;

import com.armada.account.service.AccountGroupService;
import com.armada.group.model.dto.HistoricalGroupPullCreateDTO;
import com.armada.group.model.vo.HistoricalGroupDetailVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import org.springframework.stereotype.Component;

/** 历史群拉人创建前的租户资源与服务端邀请链接硬门禁。 */
@Component
public class HistoricalGroupPullCreateValidator {

    private static final int PARTICIPANT_BATCH_MAX_SIZE = 50;

    private final AccountGroupService accountGroupService;
    private final HistoricalGroupService historicalGroupService;

    /**
     * 创建资源校验器。
     *
     * @param accountGroupService  当前租户账号分组服务
     * @param historicalGroupService 账号组历史范围与实时群详情服务
     */
    public HistoricalGroupPullCreateValidator(
            AccountGroupService accountGroupService,
            HistoricalGroupService historicalGroupService) {
        this.accountGroupService = accountGroupService;
        this.historicalGroupService = historicalGroupService;
    }

    /**
     * 校验创建参数和当前租户资源，并从服务端重新读取邀请链接。
     *
     * <p>不检查 {@code operationAllowed}：该字段只约束管理员成员操作，拉手踩链接拉人
     * 只要求目标属于账号组历史范围且服务端此刻能取到非空邀请链接。</p>
     *
     * @param request 创建元数据
     * @return 含 fresh 群名和邀请链接的实时详情
     * @throws BusinessException 参数、账号组、历史群范围或邀请链接不合法时抛出
     */
    public HistoricalGroupDetailVO validateAndLoadFreshDetail(HistoricalGroupPullCreateDTO request) {
        validateFields(request);
        accountGroupService.requireExisting(request.sourceAccountGroupId());
        accountGroupService.requireExisting(request.pullerAccountGroupId());
        HistoricalGroupDetailVO detail = historicalGroupService.getHistoricalGroupDetail(
                request.sourceAccountGroupId(), request.groupJid().trim());
        if (!detail.linkAvailable() || !hasText(detail.inviteUrl())) {
            throw new BusinessException(ErrorCode.VALIDATION, "当前无法取得群邀请链接，不能创建拉人执行");
        }
        return detail;
    }

    private static void validateFields(HistoricalGroupPullCreateDTO request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "创建参数不能为空");
        }
        if (request.sourceAccountGroupId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "来源账号组 ID 不能为空");
        }
        if (!hasText(request.groupJid())) {
            throw new BusinessException(ErrorCode.VALIDATION, "目标群 JID 不能为空");
        }
        if (request.pullerAccountGroupId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择拉手账号分组");
        }
        if (request.singleAddCount() == null
                || request.singleAddCount() < 1
                || request.singleAddCount() > PARTICIPANT_BATCH_MAX_SIZE) {
            throw new BusinessException(
                    ErrorCode.VALIDATION,
                    "单次添加人数必须在 1 到 " + PARTICIPANT_BATCH_MAX_SIZE + " 之间");
        }
        if (!hasText(request.idempotencyKey()) || request.idempotencyKey().trim().length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION, "幂等键不能为空且不能超过 128 个字符");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
