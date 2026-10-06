package com.armada.account.service.impl;

import com.armada.account.mapper.AccountCreatorDeletionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.armada.account.model.dto.CreatorDeletionBinding;
import com.armada.account.model.dto.CreatorReservationRequest;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCreatorDeletion;
import com.armada.account.model.enums.AccountCreatorDeletionLifecycle;
import com.armada.account.service.AccountCreatorDeletionService;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 一次性账号的原子占用和不可逆注销生命周期，账号记录与历史关系始终保留。 */
@Service
public class AccountCreatorDeletionServiceImpl implements AccountCreatorDeletionService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AccountCreatorDeletionMapper mapper;

    /** 使用持久化身份唯一键与账号行锁，避免进程内锁造成跨实例重复选号。 */
    public AccountCreatorDeletionServiceImpl(AccountCreatorDeletionMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void validateCreatorGroup(Long groupId) {
        long tenantId = currentTenant();
        if (groupId == null || mapper.supportedGroupCount(tenantId, groupId) == 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "注销建群账号仅支持可用的 Android 主设备建群分组");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean reserve(CreatorReservationRequest request) {
        requireTenant(request.tenantId());
        ProtocolAccountRef creator = request.creator();
        if (creator == null || creator.backend() != ProtocolBackend.ANDROID
                || blank(request.createOperationId())) return false;
        mapper.lockIdentityAliases(normalizedPhone(creator.wsPhone()));
        Account account = mapper.lockAccount(request.tenantId(), creator.armadaAccountId());
        if (!sameAccount(account, creator)
                || mapper.eligibleAccount(request.tenantId(), creator.armadaAccountId()) == null) return false;
        AccountCreatorDeletion existing = mapper.byExecution(request.tenantId(), request.executionId());
        if (existing != null) {
            return existing.getTaskId() == request.taskId()
                    && existing.getAccountId().equals(creator.armadaAccountId())
                    && existing.getIdentityHash().equals(identityHash(creator))
                    && existing.getCreateOperationId().equals(request.createOperationId())
                    && AccountCreatorDeletionLifecycle.RESERVED.name().equals(existing.getLifecycle());
        }
        AccountCreatorDeletion row = reservation(request);
        if (hasOtherDependencies(row)) return false;
        try {
            return mapper.insert(row) == 1;
        } catch (DuplicateKeyException occupied) {
            // identity_hash 全局唯一：同号不同账号行、不同租户也只能有一个一次性拥有者。
            return false;
        }
    }

    @Override
    public Optional<ProtocolAccountRef> findReservedCreator(long executionId) {
        long tenantId = currentTenant();
        AccountCreatorDeletion row = mapper.byExecution(tenantId, executionId);
        if (row == null || !AccountCreatorDeletionLifecycle.RESERVED.name().equals(row.getLifecycle())) {
            return Optional.empty();
        }
        Account current = mapper.eligibleAccount(tenantId, row.getAccountId());
        if (current == null) return Optional.empty();
        ProtocolAccountRef ref = new ProtocolAccountRef(current.getId(), ProtocolBackend.ANDROID,
                current.getProtocolAccountId(), current.getWsPhone());
        return identityHash(ref).equals(row.getIdentityHash())
                && ref.protocolAccountId().equals(row.getProtocolAccountId())
                ? Optional.of(ref) : Optional.empty();
    }

    @Override
    public String identityHash(ProtocolAccountRef creator) {
        String phone = normalizedPhone(creator.wsPhone());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(phone.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime lacks SHA-256", impossible);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean beginDeletion(CreatorDeletionBinding binding, long now) {
        requireTenant(binding.tenantId());
        AccountCreatorDeletion row = mapper.byExecution(binding.tenantId(), binding.executionId());
        if (!matches(row, binding)) return false;
        mapper.lockIdentityAliases(row.getCreatorPhone());
        Account locked = mapper.lockAccount(binding.tenantId(), binding.accountId());
        row = mapper.byExecution(binding.tenantId(), binding.executionId());
        if (locked == null || !matches(row, binding) || blank(binding.operationId())) return false;
        if (AccountCreatorDeletionLifecycle.DELETING.name().equals(row.getLifecycle())) {
            return binding.operationId().equals(row.getOperationId());
        }
        if (!AccountCreatorDeletionLifecycle.RESERVED.name().equals(row.getLifecycle())
                || findReservedCreator(binding.executionId()).isEmpty()
                || hasOtherDependencies(row)) return false;
        if (mapper.begin(binding, now) != 1) return false;
        mapper.freezeOnline(binding.tenantId(), binding.accountId(), now);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void completeDeletion(CreatorDeletionBinding binding, long now) {
        requireTenant(binding.tenantId());
        AccountCreatorDeletion row = mapper.byExecution(binding.tenantId(), binding.executionId());
        if (row != null) mapper.lockIdentityAliases(row.getCreatorPhone());
        mapper.lockAccount(binding.tenantId(), binding.accountId());
        row = mapper.byExecution(binding.tenantId(), binding.executionId());
        if (!matches(row, binding) || !Objects.equals(binding.operationId(), row.getOperationId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "注销生命周期身份或操作不匹配");
        }
        if (AccountCreatorDeletionLifecycle.DELETED.name().equals(row.getLifecycle())) return;
        if (mapper.complete(binding, now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "注销生命周期尚未进入待验证状态");
        }
        mapper.markOffline(binding.tenantId(), binding.accountId(), now);
        mapper.releaseCreator(binding, now);
    }

    /** 未发送的未来步骤也可能已绑定该身份；只按冻结 ID 比较，不做文本模糊匹配。 */
    private boolean hasOtherDependencies(AccountCreatorDeletion row) {
        if (mapper.hasOtherDependencies(row)) return true;
        for (var binding : mapper.activeScriptBindings(row.getCreatorPhone())) {
            try {
                JsonNode steps = JSON.readTree(binding.stepsJson());
                if (steps == null || !steps.isArray()) return true;
                for (JsonNode step : steps) {
                    if (!step.isObject()) return true;
                    if (matchesAccountId(step.get("accountId"), binding.accountId())) return true;
                }
                if (!blank(binding.bindingsJson())) {
                    JsonNode roles = JSON.readTree(binding.bindingsJson());
                    if (roles == null || !roles.isObject()) return true;
                    for (JsonNode accountId : roles) {
                        if (matchesAccountId(accountId, binding.accountId())) return true;
                    }
                }
            } catch (java.io.IOException | IllegalArgumentException malformed) {
                // 运行中冻结配置损坏时不能证明没有依赖，也不能把内容写入日志。
                return true;
            }
        }
        return false;
    }

    private boolean matchesAccountId(JsonNode value, Long accountId) {
        if (value == null || value.isNull()) return false;
        if (value.isIntegralNumber() && value.canConvertToLong()) return value.longValue() == accountId;
        if (value.isTextual()) return Long.parseLong(value.textValue()) == accountId;
        throw new IllegalArgumentException("无效的冻结角色账号 ID");
    }

    private AccountCreatorDeletion reservation(CreatorReservationRequest request) {
        AccountCreatorDeletion row = new AccountCreatorDeletion();
        row.setTenantId(request.tenantId());
        row.setTaskId(request.taskId());
        row.setGroupExecutionId(request.executionId());
        row.setAccountId(request.creator().armadaAccountId());
        row.setCreatorPhone(normalizedPhone(request.creator().wsPhone()));
        row.setIdentityHash(identityHash(request.creator()));
        row.setProtocolAccountId(request.creator().protocolAccountId());
        row.setCreateOperationId(request.createOperationId());
        row.setLifecycle(AccountCreatorDeletionLifecycle.RESERVED.name());
        row.setCreatedAt(request.now());
        row.setUpdatedAt(request.now());
        return row;
    }

    private boolean matches(AccountCreatorDeletion row, CreatorDeletionBinding binding) {
        return row != null && row.getTenantId() == binding.tenantId() && row.getTaskId() == binding.taskId()
                && row.getGroupExecutionId() == binding.executionId() && row.getAccountId() == binding.accountId()
                && Objects.equals(row.getIdentityHash(), binding.identityHash())
                && Objects.equals(row.getCreateOperationId(), binding.createOperationId());
    }

    private boolean sameAccount(Account account, ProtocolAccountRef ref) {
        return account != null && Objects.equals(account.getProtocolAccountId(), ref.protocolAccountId())
                && normalizedPhone(account.getWsPhone()).equals(normalizedPhone(ref.wsPhone()));
    }

    private String normalizedPhone(String phone) {
        String normalized = phone == null ? "" : phone.trim().replace("+", "").replace(" ", "");
        if (!normalized.matches("[0-9]{5,20}")) {
            throw new BusinessException(ErrorCode.VALIDATION, "建群账号身份不是有效的规范化号码");
        }
        return normalized;
    }

    private static long currentTenant() {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId <= 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "缺少账号注销租户上下文");
        }
        return tenantId;
    }

    private static void requireTenant(long tenantId) {
        if (currentTenant() != tenantId) {
            throw new BusinessException(ErrorCode.CONFLICT, "账号注销租户上下文不匹配");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
