package com.armada.account.wbcompanion.mapper;

import com.armada.account.wbcompanion.model.entity.WbCompanionBinding;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 安卓号当前 WB 分身绑定（wb_companion_binding）的数据访问，受租户拦截。 */
@Mapper
public interface WbCompanionBindingMapper {

    /** 新建绑定；同一账号已有绑定时唯一键冲突。 */
    int insert(WbCompanionBinding binding);

    /** 按账号读取绑定。 */
    WbCompanionBinding selectByAccountId(@Param("accountId") Long accountId);

    /** 按账号批量读取绑定，账号列表列使用。 */
    List<WbCompanionBinding> selectByAccountIds(@Param("accountIds") List<Long> accountIds);

    /** 更新设备和连接数快照。 */
    int updateSnapshot(WbCompanionBinding binding);

    /** 解绑或 WB 上已没有设备时删除。 */
    int deleteByAccountId(@Param("accountId") Long accountId);
}
