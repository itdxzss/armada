package com.armada.account.wbcompanion.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountLoginStateCode;
import com.armada.account.wbcompanion.mapper.WbCompanionBindingMapper;
import com.armada.account.wbcompanion.mapper.WbCompanionTaskMapper;
import com.armada.account.wbcompanion.model.dto.WbCompanionCreateDTO;
import com.armada.account.wbcompanion.model.entity.WbCompanionBinding;
import com.armada.account.wbcompanion.model.entity.WbCompanionTask;
import com.armada.account.wbcompanion.model.enums.WbCompanionStep;
import com.armada.account.wbcompanion.model.enums.WbCompanionTaskStatus;
import com.armada.account.wbcompanion.model.vo.WbCompanionVOs;
import com.armada.account.wbcompanion.service.WbCompanionBindingService;
import com.armada.account.wbcompanion.service.WbCompanionService;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.wbpro.WbproIntegrationClient;
import com.armada.platform.wbpro.WbproIntegrationException;
import com.armada.platform.wbpro.WbproProperties;
import com.armada.platform.wbpro.model.WbproModels;
import com.armada.platform.wbpro.model.WbproTarget;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 发起前在 Armada 侧拦住明显不行的情况（不是安卓号、不在线、已绑定别的会员、有进行中任务、刚失败），
 * 其余规则（名额、影子上限、来源冲突）以 WB 返回为准。任务创建后由 WbCompanionScheduler 推进。
 */
@Service
public class WbCompanionServiceImpl implements WbCompanionService {

    private static final int MAX_BATCH = 200;
    private static final int MAX_SUMMARY = 500;
    private static final int MAX_CONNECTIONS = 4;
    private static final int RECENT_TASKS = 10;
    /** 失败后同一账号的冷却：安卓层 120 秒内会把残留的未开始配对请求和新请求一起算，返回 AMBIGUOUS。 */
    static final long FAILURE_COOLDOWN_MILLIS = 120_000L;
    private static final String MAIN = "MAIN";
    private static final String SHADOW = "SHADOW";

    private final WbproProperties properties;
    private final WbproIntegrationClient client;
    private final AccountMapper accountMapper;
    private final WbCompanionTaskMapper taskMapper;
    private final WbCompanionBindingMapper bindingMapper;
    private final WbCompanionBindingService bindingService;

    public WbCompanionServiceImpl(WbproProperties properties, WbproIntegrationClient client,
                                  AccountMapper accountMapper, WbCompanionTaskMapper taskMapper,
                                  WbCompanionBindingMapper bindingMapper, WbCompanionBindingService bindingService) {
        this.properties = properties;
        this.client = client;
        this.accountMapper = accountMapper;
        this.taskMapper = taskMapper;
        this.bindingMapper = bindingMapper;
        this.bindingService = bindingService;
    }

    @Override
    public List<WbCompanionVOs.WbTargetVO> targets() {
        return properties.parsedTargets().stream()
                .map(target -> new WbCompanionVOs.WbTargetVO(target.envId(), target.name()))
                .toList();
    }

    @Override
    public WbCompanionVOs.WbMemberVO member(String wbEnvId, String username) {
        WbproTarget target = requireTarget(wbEnvId);
        if (username == null || username.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "请输入 WB 用户名");
        }
        try {
            WbproModels.Env env = client.env(target);
            if (!env.importEnabled()) {
                throw new BusinessException(ErrorCode.CONFLICT, "目标 WB 未开启平台导入，请先在 WB 管理端打开");
            }
            WbproModels.Member member = client.member(target, username.trim())
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "WB 用户不存在"));
            return new WbCompanionVOs.WbMemberVO(member.id(), member.username(), member.status(),
                    member.deviceCount(), member.deviceMax(), env.shadowEnabled());
        } catch (WbproIntegrationException ex) {
            throw wbFailure(ex);
        }
    }

    @Override
    public WbCompanionVOs.WbCompanionCreateResultVO create(WbCompanionCreateDTO dto, Long userId) {
        validate(dto);
        WbproTarget target = requireTarget(dto.wbEnvId());
        List<WbCompanionVOs.WbCompanionCreateResultVO.Item> items = new ArrayList<>();
        for (Long accountId : dto.accountIds().stream().distinct().toList()) {
            try {
                items.add(new WbCompanionVOs.WbCompanionCreateResultVO.Item(
                        accountId, createOne(accountId, target, dto, userId), null, null));
            } catch (BusinessException ex) {
                items.add(new WbCompanionVOs.WbCompanionCreateResultVO.Item(
                        accountId, null, Integer.toString(ex.getCode()), ex.getMessage()));
            }
        }
        return new WbCompanionVOs.WbCompanionCreateResultVO(items);
    }

    private Long createOne(Long accountId, WbproTarget target, WbCompanionCreateDTO dto, Long userId) {
        Account account = requireAndroidOnline(accountId);
        WbCompanionBinding binding = bindingMapper.selectByAccountId(accountId);
        if (binding != null && (!binding.getWbEnvId().equals(target.envId())
                || !binding.getWbMemberId().equals(dto.wbMemberId()))) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "已挂在 WB 用户 " + binding.getWbUsername() + "，要换用户请先全部解绑");
        }
        if (!taskMapper.selectRunningByAccountIds(List.of(accountId)).isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "已有进行中的登录");
        }
        long now = System.currentTimeMillis();
        requireCooledDown(accountId, now);
        int base = liveConnections(target, account, dto.wbMemberId());
        if (base >= dto.targetCount()) {
            throw new BusinessException(ErrorCode.CONFLICT, "WB 上已有 " + base + " 个连接，无需登录");
        }
        WbCompanionTask task = new WbCompanionTask();
        task.setAccountId(accountId);
        task.setWsPhone(account.getWsPhone());
        task.setWbEnvId(target.envId());
        task.setWbMemberId(dto.wbMemberId());
        task.setWbUsername(dto.wbUsername() == null ? "" : dto.wbUsername());
        task.setBaseCount(base);
        task.setTargetCount(dto.targetCount());
        task.setSucceededCount(0);
        task.setStatus(WbCompanionTaskStatus.RUNNING);
        task.setStep(WbCompanionStep.WAITING);
        task.setNextRunAt(now);
        task.setCreatedBy(userId);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "已有进行中的登录");
        }
        return task.getId();
    }

    private Account requireAndroidOnline(Long accountId) {
        Account account = accountMapper.selectActiveById(accountId);
        if (account == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "账号不存在或已删除");
        }
        if (ProtocolBackend.fromProtocolId(account.getProtocolId()) != ProtocolBackend.ANDROID) {
            throw new BusinessException(ErrorCode.VALIDATION, "只有安卓协议账号可以登录到 WB");
        }
        if (account.getWsPhone() == null || account.getWsPhone().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号未绑定登录号码");
        }
        List<Long> online = accountMapper.selectOnlineAccountIdsByIds(List.of(accountId), AccountLoginStateCode.ONLINE);
        if (online == null || !online.contains(accountId)) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号不在线，先上线再登录");
        }
        return account;
    }

    private void requireCooledDown(Long accountId, long now) {
        List<WbCompanionTask> recent = taskMapper.selectRecentByAccount(accountId, 1);
        if (recent.isEmpty()) {
            return;
        }
        Long retryAfter = retryAfter(recent.getFirst());
        if (retryAfter != null && retryAfter > now) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "上次登录失败，请 " + ((retryAfter - now) / 1000 + 1) + " 秒后再试");
        }
    }

    /** WB 上该号现有的连接数；号码已导入给其他会员时拒绝（WB 一个号码只导入给一个会员）。 */
    private int liveConnections(WbproTarget target, Account account, Long wbMemberId) {
        try {
            Optional<WbproModels.Device> device = bindingService.sync(target, account.getId(), account.getWsPhone());
            if (device.isPresent() && device.get().memberId() != wbMemberId) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "该号码已导入给 WB 用户 " + device.get().username() + "，要换用户请先全部解绑");
            }
            return device.map(WbCompanionBindingService::connections).orElse(0);
        } catch (WbproIntegrationException ex) {
            throw wbFailure(ex);
        }
    }

    @Override
    public WbCompanionVOs.WbCompanionTaskVO task(Long taskId) {
        WbCompanionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务不存在");
        }
        return toVO(task);
    }

    @Override
    public void stop(Long taskId) {
        if (taskMapper.stop(taskId, System.currentTimeMillis()) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务已结束");
        }
    }

    @Override
    public List<WbCompanionVOs.WbCompanionSummaryVO> summaries(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return List.of();
        }
        if (accountIds.size() > MAX_SUMMARY) {
            throw new BusinessException(ErrorCode.VALIDATION, "一次最多查询 " + MAX_SUMMARY + " 个账号");
        }
        Map<Long, WbCompanionBinding> bindings = bindingMapper.selectByAccountIds(accountIds).stream()
                .collect(Collectors.toMap(WbCompanionBinding::getAccountId, Function.identity()));
        Map<Long, WbCompanionTask> running = taskMapper.selectRunningByAccountIds(accountIds).stream()
                .collect(Collectors.toMap(WbCompanionTask::getAccountId, Function.identity()));
        return accountIds.stream().distinct()
                .filter(id -> bindings.containsKey(id) || running.containsKey(id))
                .map(id -> summary(id, bindings.get(id), running.get(id)))
                .toList();
    }

    private static WbCompanionVOs.WbCompanionSummaryVO summary(Long accountId, WbCompanionBinding binding,
                                                               WbCompanionTask task) {
        return new WbCompanionVOs.WbCompanionSummaryVO(accountId,
                binding != null ? binding.getWbEnvId() : task.getWbEnvId(),
                binding != null ? binding.getWbUsername() : task.getWbUsername(),
                binding == null ? 0 : binding.getConnectionCount(),
                binding == null ? 0 : binding.getOnlineCount(),
                task == null ? null : task.getId(),
                task == null ? 0 : task.getBaseCount() + task.getSucceededCount(),
                task == null ? 0 : task.getTargetCount());
    }

    @Override
    public WbCompanionVOs.WbCompanionDetailVO detail(Long accountId) {
        Account account = accountMapper.selectActiveById(accountId);
        if (account == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "账号不存在或已删除");
        }
        List<WbCompanionVOs.WbCompanionTaskVO> tasks = taskMapper.selectRecentByAccount(accountId, RECENT_TASKS)
                .stream().map(this::toVO).toList();
        WbCompanionBinding binding = bindingMapper.selectByAccountId(accountId);
        String envId = binding != null ? binding.getWbEnvId() : tasks.isEmpty() ? null : tasks.getFirst().wbEnvId();
        Optional<WbproTarget> target = envId == null ? Optional.empty() : properties.target(envId);
        if (target.isEmpty()) {
            return new WbCompanionVOs.WbCompanionDetailVO(accountId, envId, null, null, null, null, false, List.of(), tasks);
        }
        try {
            Optional<WbproModels.Device> device = bindingService.sync(target.get(), accountId, account.getWsPhone());
            return new WbCompanionVOs.WbCompanionDetailVO(accountId, envId, target.get().name(),
                    device.map(WbproModels.Device::memberId).orElse(null),
                    device.map(WbproModels.Device::username).orElse(null),
                    device.map(WbproModels.Device::deviceId).orElse(null), true,
                    device.map(WbCompanionServiceImpl::connections).orElse(List.of()), tasks);
        } catch (WbproIntegrationException ex) {
            return new WbCompanionVOs.WbCompanionDetailVO(accountId, envId, target.get().name(),
                    binding == null ? null : binding.getWbMemberId(), binding == null ? null : binding.getWbUsername(),
                    binding == null ? null : binding.getWbDeviceId(), false, List.of(), tasks);
        }
    }

    private static List<WbCompanionVOs.ConnectionVO> connections(WbproModels.Device device) {
        List<WbCompanionVOs.ConnectionVO> out = new ArrayList<>();
        out.add(new WbCompanionVOs.ConnectionVO(MAIN, device.deviceId(), device.connStatus()));
        if (device.shadows() != null) {
            device.shadows().forEach(shadow ->
                    out.add(new WbCompanionVOs.ConnectionVO(SHADOW, shadow.id(), shadow.connStatus())));
        }
        return out;
    }

    @Override
    public void unbind(Long accountId) {
        WbCompanionBinding binding = bindingMapper.selectByAccountId(accountId);
        if (binding == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该账号没有挂在 WB 的分身");
        }
        if (!taskMapper.selectRunningByAccountIds(List.of(accountId)).isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "登录进行中，先停止再解绑");
        }
        WbproTarget target = requireTarget(binding.getWbEnvId());
        if (binding.getWbDeviceId() != null) {
            try {
                client.unbind(target, binding.getWbDeviceId());
            } catch (WbproIntegrationException ex) {
                // WB 上设备已不存在（会员已删除）时视为已解绑，其余错误原样报给操作人。
                if (!"DEVICE_NOT_FOUND".equals(ex.getCode())) {
                    throw wbFailure(ex);
                }
            }
        }
        bindingMapper.deleteByAccountId(accountId);
    }

    private WbCompanionVOs.WbCompanionTaskVO toVO(WbCompanionTask task) {
        return new WbCompanionVOs.WbCompanionTaskVO(task.getId(), task.getAccountId(), task.getWsPhone(),
                task.getWbEnvId(), task.getWbUsername(), task.getBaseCount(), task.getTargetCount(),
                task.getSucceededCount(), task.getStatus(), task.getStep(), task.getWbRole(), task.getErrorCode(),
                task.getCreatedAt(), task.getFinishedAt(), retryAfter(task));
    }

    private static Long retryAfter(WbCompanionTask task) {
        return task.getStatus() == WbCompanionTaskStatus.FAILED && task.getFinishedAt() != null
                ? task.getFinishedAt() + FAILURE_COOLDOWN_MILLIS : null;
    }

    private void validate(WbCompanionCreateDTO dto) {
        if (dto == null || dto.accountIds() == null || dto.accountIds().isEmpty()
                || dto.accountIds().size() > MAX_BATCH || dto.wbMemberId() == null || dto.targetCount() == null
                || dto.targetCount() < 1 || dto.targetCount() > MAX_CONNECTIONS) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "请选择 1-" + MAX_BATCH + " 个账号、WB 用户和 1-" + MAX_CONNECTIONS + " 个分身");
        }
    }

    private WbproTarget requireTarget(String wbEnvId) {
        return properties.target(wbEnvId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION, "未配置该 WB 环境"));
    }

    private static BusinessException wbFailure(WbproIntegrationException ex) {
        return new BusinessException(ErrorCode.CONFLICT, "WB 返回失败：" + ex.getCode());
    }
}
