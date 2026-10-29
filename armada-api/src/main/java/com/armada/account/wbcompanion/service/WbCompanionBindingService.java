package com.armada.account.wbcompanion.service;

import com.armada.platform.wbpro.model.WbproModels;
import com.armada.platform.wbpro.model.WbproTarget;
import java.util.Optional;

/** 维护安卓号当前在 WB 的分身绑定快照（wb_companion_binding）。 */
public interface WbCompanionBindingService {

    /**
     * 实时查询 WB 上本接入方为该号导入的设备并刷新快照；WB 上已没有设备时删除绑定。
     *
     * @param target WB 目标
     * @param accountId 账号 ID
     * @param wsPhone 号码
     * @return WB 上的设备；没有时为空
     */
    Optional<WbproModels.Device> sync(WbproTarget target, Long accountId, String wsPhone);

    /**
     * WB 上这台设备的连接数（主连接 + 影子）。
     *
     * @param device WB 设备
     * @return 连接数
     */
    static int connections(WbproModels.Device device) {
        return 1 + (device.shadows() == null ? 0 : device.shadows().size());
    }
}
