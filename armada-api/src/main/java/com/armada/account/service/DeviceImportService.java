package com.armada.account.service;

import com.armada.account.model.dto.DeviceImportDTO;
import com.armada.account.model.dto.DeviceImportDefaults;
import com.armada.account.model.vo.DeviceImportVO;

/** 手机直传凭据业务边界。 */
public interface DeviceImportService {

    /**
     * 将一个手机账号原子导入，挂起等待官方退出确认。
     * @param request 选择的账号分组、手机号码和全参原文
     * @param defaults 服务端认证产生的租户和默认值
     * @return 事务提交后可返回手机的受理结果
     * @throws com.armada.shared.exception.BusinessException 非法输入、租户不匹配或重复账号
     */
    DeviceImportVO importAccount(DeviceImportDTO request, DeviceImportDefaults defaults);

    /**
     * 确认当前令牌租户的手机已退出，将对应单行批次释放到既有队列。
     * @param batchId 手机已成功上传的批次
     * @return QUEUED 表示已放行；重复确认不会再次派发
     */
    DeviceImportVO confirmLogout(Long batchId);
}
