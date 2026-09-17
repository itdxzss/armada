package com.armada.account.service;

import com.armada.account.model.vo.CloudRegistrationDeviceVO;
import java.util.List;

/** 由启动层已校验的注册身份配置提供目录，不读取或暴露令牌。 */
public interface CloudRegistrationDeviceService {
    /** 读取当前租户已绑定云手机；缺少租户上下文时返回空列表。 */
    List<CloudRegistrationDeviceVO> currentDevices();
}
