package com.armada.account.mapper;

import com.armada.account.model.dto.DeviceRegistrationPermit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 当前设备许可查询和短事务更新，始终由租户插件过滤。 */
@Mapper
public interface DeviceRegistrationPermitMapper {
    /** 查当前租户指定设备的采购授权。 */
    DeviceRegistrationPermit find(String deviceId);
    /** 锁定唯一设备行，与开始采购和更换许可串行化。 */
    DeviceRegistrationPermit lock(String deviceId);
    /** 新设备首笔许可，唯一索引防止并发首建。 */
    int insert(@Param("permit") DeviceRegistrationPermit permit, @Param("now") long now);
    /** 更新已锁定设备的当前许可，不更新任何历史任务。 */
    int update(@Param("permit") DeviceRegistrationPermit permit, @Param("now") long now);
}
