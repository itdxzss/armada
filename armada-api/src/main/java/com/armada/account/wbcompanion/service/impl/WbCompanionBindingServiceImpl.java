package com.armada.account.wbcompanion.service.impl;

import com.armada.account.wbcompanion.mapper.WbCompanionBindingMapper;
import com.armada.account.wbcompanion.model.entity.WbCompanionBinding;
import com.armada.account.wbcompanion.service.WbCompanionBindingService;
import com.armada.platform.wbpro.WbproIntegrationClient;
import com.armada.platform.wbpro.model.WbproModels;
import com.armada.platform.wbpro.model.WbproTarget;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 绑定快照以 WB 实时查询为准：WB 有设备就写入或更新，没有就删除。 */
@Service
public class WbCompanionBindingServiceImpl implements WbCompanionBindingService {

    private static final String CONNECTED = "CONNECTED";

    private final WbproIntegrationClient client;
    private final WbCompanionBindingMapper bindingMapper;

    public WbCompanionBindingServiceImpl(WbproIntegrationClient client, WbCompanionBindingMapper bindingMapper) {
        this.client = client;
        this.bindingMapper = bindingMapper;
    }

    @Override
    public Optional<WbproModels.Device> sync(WbproTarget target, Long accountId, String wsPhone) {
        List<WbproModels.Device> devices = client.devices(target, wsPhone);
        if (devices.isEmpty()) {
            bindingMapper.deleteByAccountId(accountId);
            return Optional.empty();
        }
        WbproModels.Device device = devices.getFirst();
        long now = System.currentTimeMillis();
        WbCompanionBinding binding = new WbCompanionBinding();
        binding.setAccountId(accountId);
        binding.setWbEnvId(target.envId());
        binding.setWbMemberId(device.memberId());
        binding.setWbUsername(device.username() == null ? "" : device.username());
        binding.setWbDeviceId(device.deviceId());
        binding.setConnectionCount(WbCompanionBindingService.connections(device));
        binding.setOnlineCount(online(device));
        binding.setSyncedAt(now);
        binding.setUpdatedAt(now);
        WbCompanionBinding existing = bindingMapper.selectByAccountId(accountId);
        // WB 一个号码只导入给一个会员；会员变化（解绑后重新导入给别人）时整行换掉，不留旧会员快照。
        if (existing != null && !existing.getWbMemberId().equals(device.memberId())) {
            bindingMapper.deleteByAccountId(accountId);
            existing = null;
        }
        if (existing == null) {
            binding.setCreatedAt(now);
            bindingMapper.insert(binding);
        } else {
            bindingMapper.updateSnapshot(binding);
        }
        return Optional.of(device);
    }

    private static int online(WbproModels.Device device) {
        int online = CONNECTED.equals(device.connStatus()) ? 1 : 0;
        if (device.shadows() != null) {
            online += (int) device.shadows().stream().filter(shadow -> CONNECTED.equals(shadow.connStatus())).count();
        }
        return online;
    }
}
