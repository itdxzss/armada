package com.armada.account.service;

import com.armada.account.model.dto.DeviceRegistrationIdentity;
import com.armada.account.model.dto.DeviceRegistrationBeginDTO;
import com.armada.account.model.dto.DeviceRegistrationPermit;
import com.armada.account.model.dto.DeviceRegistrationSetupDTO;
import com.armada.account.model.dto.DeviceRegistrationStartDTO;
import com.armada.platform.sms.grizzly.model.GrizzlyPriceTier;
import java.util.List;

/** 手机可明确发起单号采购，控端保留人工管理能力。 */
public interface DeviceRegistrationPermitService {
    /** 为认证设备准备或接续同一单号任务，不依赖控端预配置。 */
    DeviceRegistrationPermit begin(DeviceRegistrationIdentity identity, DeviceRegistrationBeginDTO request);
    /** 查当前租户设备许可；任务已开始时返回实际锁定商家。 */
    DeviceRegistrationPermit current(DeviceRegistrationIdentity identity);
    /** 校验实时报价后保存一笔新许可，不采购。 */
    DeviceRegistrationPermit prepare(DeviceRegistrationSetupDTO request);
    /** 校验已确认的请求ID和商家，返回本次执行约束。 */
    DeviceRegistrationPermit select(DeviceRegistrationPermit permit, DeviceRegistrationStartDTO request);
    /** 查询本许可国家/价格对应的当前商家。 */
    List<GrizzlyPriceTier> options(DeviceRegistrationPermit permit);
}
