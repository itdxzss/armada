package com.armada.account.service;

import com.armada.account.model.dto.DeviceImportDTO;
import com.armada.account.model.dto.DeviceImportDefaults;
import com.armada.account.model.vo.DeviceImportVO;

/** 手机直传凭据业务边界。 */
public interface DeviceImportService {

    /**
     * 将一个手机账号原子导入既有 QUEUED 链路。
     * @param request 手机号码和全参原文
     * @param defaults 服务端认证产生的租户和默认值
     * @return 事务提交后可返回手机的受理结果
     * @throws com.armada.shared.exception.BusinessException 非法输入、租户不匹配或重复账号
     */
    DeviceImportVO importAccount(DeviceImportDTO request, DeviceImportDefaults defaults);
}
