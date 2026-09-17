package com.armada.account.service;

import com.armada.account.model.dto.DeviceRegistrationPermit;
import com.armada.account.model.dto.DeviceRegistrationResultDTO;
import com.armada.account.model.vo.DeviceRegistrationVO;

/** 单许可、单手机的注册入口。 */
public interface DeviceRegistrationService {
    /** 创建或恢复同一笔采购任务。 */
    DeviceRegistrationVO start(DeviceRegistrationPermit permit);
    /** 查询本许可任务及当前收到的验证码。 */
    DeviceRegistrationVO status(DeviceRegistrationPermit permit);
    /** 控端只读状态，不轮询供应商、不返回验证码。 */
    DeviceRegistrationVO inspect(DeviceRegistrationPermit permit);
    /** 结束手机任务，不执行注册后的账号操作。 */
    DeviceRegistrationVO result(DeviceRegistrationPermit permit, DeviceRegistrationResultDTO result);
}
