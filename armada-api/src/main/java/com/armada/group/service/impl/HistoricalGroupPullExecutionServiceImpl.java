package com.armada.group.service.impl;

import com.armada.account.service.AccountProtocolLookupService;
import com.armada.group.mapper.HistoricalGroupPullExecutionMapper;
import com.armada.group.mapper.HistoricalGroupPullMemberMapper;
import com.armada.group.model.dto.HistoricalGroupPullCreateDTO;
import com.armada.group.model.entity.HistoricalGroupPullExecution;
import com.armada.group.model.entity.HistoricalGroupPullMember;
import com.armada.group.model.enums.HistoricalGroupAddStatus;
import com.armada.group.model.enums.HistoricalGroupContactStatus;
import com.armada.group.model.enums.HistoricalGroupMarketingStatus;
import com.armada.group.model.enums.HistoricalGroupMaterialType;
import com.armada.group.model.enums.HistoricalGroupMemberSendStatus;
import com.armada.group.model.enums.HistoricalGroupPullStatus;
import com.armada.group.model.vo.HistoricalGroupDetailVO;
import com.armada.group.model.vo.HistoricalGroupPullExecutionVO;
import com.armada.group.service.HistoricalGroupMaterialParser;
import com.armada.group.service.HistoricalGroupPullCreateValidator;
import com.armada.group.service.HistoricalGroupPullDispatchTrigger;
import com.armada.group.service.HistoricalGroupPullExecutionService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeAccess;
import com.armada.shared.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 历史群拉人待执行创建、启动与查询实现。
 *
 * <p>创建只固化一次性执行和成员计划；启动重新校验服务端门禁并原子认领，事务提交后再派发 worker。
 * 同租户幂等键重复时直接返回原记录，不再次解析文件、读取邀请链接或插入成员。</p>
 */
@Service
public class HistoricalGroupPullExecutionServiceImpl implements HistoricalGroupPullExecutionService {

    private final HistoricalGroupMaterialParser parser;
    private final HistoricalGroupPullCreateValidator validator;
    private final AccountProtocolLookupService accountLookupService;
    private final HistoricalGroupPullExecutionMapper executionMapper;
    private final HistoricalGroupPullMemberMapper memberMapper;
    private final HistoricalGroupPullDispatchTrigger dispatchTrigger;

    /**
     * 创建历史群拉人执行服务。
     *
     * @param parser               统一料子解析器
     * @param validator            baseline、租户资源和 fresh 邀请链接校验器
     * @param accountLookupService 营销号码批量账号匹配服务
     * @param executionMapper      执行数据访问
     * @param memberMapper         成员数据访问
     * @param dispatchTrigger      事务提交后拉人派发器
     */
    public HistoricalGroupPullExecutionServiceImpl(
            HistoricalGroupMaterialParser parser,
            HistoricalGroupPullCreateValidator validator,
            AccountProtocolLookupService accountLookupService,
            HistoricalGroupPullExecutionMapper executionMapper,
            HistoricalGroupPullMemberMapper memberMapper,
            HistoricalGroupPullDispatchTrigger dispatchTrigger) {
        this.parser = parser;
        this.validator = validator;
        this.accountLookupService = accountLookupService;
        this.executionMapper = executionMapper;
        this.memberMapper = memberMapper;
        this.dispatchTrigger = dispatchTrigger;
    }

    /**
     * 解析料子并事务写入待执行聚合。
     *
     * <p>先查幂等键，命中时不执行任何创建副作用；新请求必须经 Task7 实时详情重新取得邀请链接。
     * 营销号码只做一次当前租户批量账号查询，未匹配号码仍落库，发送阶段再记录明确失败。</p>
     *
     * @param request multipart 元数据
     * @param file    料子文件
     * @return 新建或幂等命中的执行详情
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public HistoricalGroupPullExecutionVO create(
            HistoricalGroupPullCreateDTO request,
            MultipartFile file) {
        Long tenantId = requireTenantId();
        DataScope scope = DataScopeAccess.requireCurrent();
        long ownerUserId = scope.ownerUserIdForCreate();
        String idempotencyKey = requireIdempotencyKey(request);
        HistoricalGroupPullExecution existing =
                executionMapper.selectByTenantOwnerAndIdempotencyKey(
                        tenantId, ownerUserId, idempotencyKey);
        if (existing != null) {
            return toVO(existing, memberMapper.selectOrderedByExecutionId(existing.getId()));
        }

        HistoricalGroupDetailVO detail = validator.validateForCreateAndLoadFreshDetail(request);
        HistoricalGroupMaterialParser.ParseResult parsed = parser.parse(file);
        if (parsed.members().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "料子中没有有效手机号");
        }
        Map<String, ProtocolAccountRef> marketingAccounts = accountLookupService.findActiveProtocolRefsByPhones(
                marketingPhones(parsed.members()));
        long now = System.currentTimeMillis();
        HistoricalGroupPullExecution execution = buildExecution(
                request, detail, parsed, idempotencyKey, ownerUserId, now);
        try {
            executionMapper.insert(execution);
        } catch (DuplicateKeyException ex) {
            return concurrentExistingOrThrow(tenantId, ownerUserId, idempotencyKey, ex);
        }
        List<HistoricalGroupPullMember> members =
                buildMembers(execution.getId(), parsed.members(), marketingAccounts, now);
        memberMapper.batchInsert(members);
        return toVO(execution, memberMapper.selectOrderedByExecutionId(execution.getId()));
    }

    /**
     * 重新读取服务端门禁后原子认领并在事务提交后派发执行。
     *
     * <p>先判断当前状态，保证重复启动不会触发详情协议调用。fresh 邀请链接只证明当前仍可启动，
     * 不覆盖创建时固化链接，因此 worker 只有一个邀请链接事实来源。</p>
     *
     * @param id 待启动执行 ID
     * @return 已进入运行态的执行详情
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public HistoricalGroupPullExecutionVO start(Long id) {
        if (id == null || id < 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "执行 ID 必须大于 0");
        }
        Long tenantId = requireTenantId();
        DataScope scope = DataScopeAccess.requireCurrent();
        HistoricalGroupPullExecution execution =
                executionMapper.selectByTenantAndIdForScope(tenantId, id, scope);
        if (execution == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "历史群拉人执行不存在: " + id);
        }
        if (execution.getPullStatus() != HistoricalGroupPullStatus.PENDING.code()) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有待执行状态可以启动");
        }
        if (execution.getOwnerUserId() == null) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED,
                    "历史无归属执行不能启动，请重新创建执行");
        }
        HistoricalGroupDetailVO detail = validator.validateExistingAndLoadFreshDetail(
                startValidationRequest(execution), execution.getOwnerUserId());
        long now = System.currentTimeMillis();
        executionMapper.updateOperationAccountIfPending(
                id,
                detail.accountId(),
                HistoricalGroupPullStatus.PENDING.code(),
                now);
        int claimed = executionMapper.claimStatus(
                id,
                HistoricalGroupPullStatus.PENDING.code(),
                HistoricalGroupPullStatus.RUNNING.code(),
                now);
        if (claimed != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "执行状态已变化，请刷新后重试");
        }
        dispatchTrigger.dispatchAfterCommit(tenantId, id);
        HistoricalGroupPullExecution running =
                executionMapper.selectByTenantAndIdForScope(tenantId, id, scope);
        return requireExecution(running, "历史群拉人执行不存在: " + id);
    }

    /** {@inheritDoc} */
    @Override
    public HistoricalGroupPullExecutionVO getById(Long id) {
        if (id == null || id < 1) {
            throw new BusinessException(ErrorCode.VALIDATION, "执行 ID 必须大于 0");
        }
        HistoricalGroupPullExecution execution =
                executionMapper.selectByTenantAndIdForScope(
                        requireTenantId(), id, DataScopeAccess.requireCurrent());
        return requireExecution(execution, "历史群拉人执行不存在: " + id);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<HistoricalGroupPullExecutionVO> latest(Long sourceAccountGroupId, String groupJid) {
        if (sourceAccountGroupId == null || groupJid == null || groupJid.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "来源账号组 ID 和目标群 JID 不能为空");
        }
        HistoricalGroupPullExecution execution =
                executionMapper.selectLatestByTenantSourceGroupAndGroupForScope(
                        requireTenantId(), sourceAccountGroupId, groupJid.trim(),
                        DataScopeAccess.requireCurrent());
        if (execution == null) {
            return Optional.empty();
        }
        return Optional.of(toVO(execution, memberMapper.selectOrderedByExecutionId(execution.getId())));
    }

    private HistoricalGroupPullExecutionVO concurrentExistingOrThrow(
            Long tenantId,
            Long ownerUserId,
            String idempotencyKey,
            DuplicateKeyException cause) {
        HistoricalGroupPullExecution existing =
                executionMapper.selectByTenantOwnerAndIdempotencyKeyForUpdate(
                        tenantId, ownerUserId, idempotencyKey);
        if (existing == null) {
            throw cause;
        }
        return toVO(existing, memberMapper.selectOrderedByExecutionId(existing.getId()));
    }

    private HistoricalGroupPullExecutionVO requireExecution(
            HistoricalGroupPullExecution execution,
            String missingMessage) {
        if (execution == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, missingMessage);
        }
        return toVO(execution, memberMapper.selectOrderedByExecutionId(execution.getId()));
    }

    private static HistoricalGroupPullExecution buildExecution(
            HistoricalGroupPullCreateDTO request,
            HistoricalGroupDetailVO detail,
            HistoricalGroupMaterialParser.ParseResult parsed,
            String idempotencyKey,
            Long ownerUserId,
            long now) {
        HistoricalGroupPullExecution row = new HistoricalGroupPullExecution();
        row.setOwnerUserId(ownerUserId);
        row.setCreatedBy(ownerUserId);
        row.setIdempotencyKey(idempotencyKey);
        row.setOperationAccountId(detail.accountId());
        row.setSourceAccountGroupId(request.sourceAccountGroupId());
        row.setGroupJid(request.groupJid().trim());
        row.setGroupSubjectSnapshot(trimToNull(detail.subject()));
        row.setInviteLink(detail.inviteUrl().trim());
        row.setPullerAccountGroupId(request.pullerAccountGroupId());
        row.setSingleAddCount(request.singleAddCount());
        row.setNormalCount(parsed.normalCount());
        row.setMarketingCount(parsed.marketingCount());
        row.setInvalidCount(parsed.invalidCount());
        row.setDuplicateCount(parsed.duplicateCount());
        row.setPullSuccessCount(0);
        row.setPullFailureCount(0);
        row.setSendSuccessCount(0);
        row.setSendFailureCount(0);
        row.setPullStatus(HistoricalGroupPullStatus.PENDING.code());
        row.setMarketingStatus(parsed.marketingCount() == 0
                ? HistoricalGroupMarketingStatus.NOT_APPLICABLE.code()
                : HistoricalGroupMarketingStatus.NOT_STARTED.code());
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        return row;
    }

    private static List<HistoricalGroupPullMember> buildMembers(
            Long executionId,
            List<HistoricalGroupMaterialParser.ParsedMember> parsed,
            Map<String, ProtocolAccountRef> marketingAccounts,
            long now) {
        List<HistoricalGroupPullMember> rows = new ArrayList<>(parsed.size());
        for (HistoricalGroupMaterialParser.ParsedMember source : parsed) {
            HistoricalGroupPullMember row = new HistoricalGroupPullMember();
            row.setExecutionId(executionId);
            row.setLineNo(source.lineNo());
            row.setPhone(source.phone());
            row.setMaterialType(source.materialType().code());
            ProtocolAccountRef account = marketingAccounts.get(source.phone());
            if (source.materialType() == HistoricalGroupMaterialType.MARKETING && account != null) {
                row.setAccountId(account.armadaAccountId());
                row.setProtocolAccountIdSnapshot(account.protocolAccountId());
            }
            row.setContactStatus(HistoricalGroupContactStatus.PENDING.code());
            row.setAddStatus(HistoricalGroupAddStatus.PENDING.code());
            row.setSendStatus(source.materialType() == HistoricalGroupMaterialType.MARKETING
                    ? HistoricalGroupMemberSendStatus.PENDING.code()
                    : HistoricalGroupMemberSendStatus.NOT_APPLICABLE.code());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            rows.add(row);
        }
        return List.copyOf(rows);
    }

    private static List<String> marketingPhones(
            List<HistoricalGroupMaterialParser.ParsedMember> members) {
        return members.stream()
                .filter(member -> member.materialType() == HistoricalGroupMaterialType.MARKETING)
                .map(HistoricalGroupMaterialParser.ParsedMember::phone)
                .toList();
    }

    private HistoricalGroupPullExecutionVO toVO(
            HistoricalGroupPullExecution execution,
            List<HistoricalGroupPullMember> members) {
        List<HistoricalGroupPullExecutionVO.MemberVO> memberVOs = members.stream()
                .map(HistoricalGroupPullExecutionServiceImpl::toMemberVO)
                .toList();
        ProtocolAccountRef puller = execution.getPullerAccountId() == null
                ? null
                : accountLookupService.findActiveProtocolRef(execution.getPullerAccountId()).orElse(null);
        String pullerPhone = puller == null ? null : puller.wsPhone();
        return new HistoricalGroupPullExecutionVO(
                execution.getId(), execution.getIdempotencyKey(), execution.getOperationAccountId(),
                execution.getSourceAccountGroupId(),
                execution.getGroupJid(), execution.getGroupSubjectSnapshot(), execution.getInviteLink(),
                execution.getPullerAccountGroupId(), execution.getPullerAccountId(), pullerPhone,
                pullerPhone == null ? null : WhatsappJids.userJid(pullerPhone), execution.getSingleAddCount(),
                execution.getMarketingTemplateId(), execution.getNormalCount(), execution.getMarketingCount(),
                execution.getInvalidCount(), execution.getDuplicateCount(), execution.getPullSuccessCount(),
                execution.getPullFailureCount(), execution.getSendSuccessCount(), execution.getSendFailureCount(),
                HistoricalGroupPullStatus.fromCode(execution.getPullStatus()),
                HistoricalGroupMarketingStatus.fromCode(execution.getMarketingStatus()), execution.getFailureStage(),
                execution.getErrorCode(), execution.getErrorMessage(), execution.getStartedAt(),
                execution.getFinishedAt(), execution.getCreatedAt(), execution.getUpdatedAt(), memberVOs);
    }

    private static HistoricalGroupPullExecutionVO.MemberVO toMemberVO(HistoricalGroupPullMember member) {
        return new HistoricalGroupPullExecutionVO.MemberVO(
                member.getId(), member.getLineNo(), member.getPhone(), WhatsappJids.userJid(member.getPhone()),
                HistoricalGroupMaterialType.fromCode(member.getMaterialType()), member.getAccountId(),
                member.getProtocolAccountIdSnapshot(),
                HistoricalGroupContactStatus.fromCode(member.getContactStatus()),
                member.getContactErrorCode(), member.getContactErrorMessage(),
                HistoricalGroupAddStatus.fromCode(member.getAddStatus()), member.getAddErrorCode(),
                member.getAddErrorMessage(), HistoricalGroupMemberSendStatus.fromCode(member.getSendStatus()),
                member.getSendCommandId(), member.getSendResultEventId(),
                member.getSendErrorCode(), member.getSendErrorMessage());
    }

    private static HistoricalGroupPullCreateDTO startValidationRequest(
            HistoricalGroupPullExecution execution) {
        return new HistoricalGroupPullCreateDTO(
                execution.getSourceAccountGroupId(),
                execution.getGroupJid(),
                execution.getPullerAccountGroupId(),
                execution.getSingleAddCount(),
                execution.getIdempotencyKey());
    }

    private static Long requireTenantId() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        return tenantId;
    }

    private static String requireIdempotencyKey(HistoricalGroupPullCreateDTO request) {
        if (request == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "幂等键不能为空");
        }
        String key = request.idempotencyKey().trim();
        if (key.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION, "幂等键不能超过 128 个字符");
        }
        return key;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
