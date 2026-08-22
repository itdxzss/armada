package com.armada.group.service;

import com.armada.group.model.vo.GroupCreatorLeaveCapabilityVO;
import com.armada.group.model.vo.GroupCreatorLeavePlan;
import com.armada.group.model.vo.GroupCreatorLeaveResultVO;

/** 基于当前数据库群事实执行管理权限交接与建群者退群。 */
public interface GroupCreatorLeaveService {

    /** 读取本地投影判断手动按钮是否可用，不调用协议层。 */
    GroupCreatorLeaveCapabilityVO capability(Long groupLinkId);

    /**
     * 使用事件实时维护的本地投影生成退群计划，不调用协议 metadata。
     *
     * @param groupLinkId 群链接 ID
     * @param preferredCreatorAccountId 指定建群者账号；手动入口为空
     * @return 已有控端管理员时不带提升对象；否则带一个待提升的普通控端成员
     */
    GroupCreatorLeavePlan plan(Long groupLinkId, Long preferredCreatorAccountId);

    /**
     * 执行群主退群。
     *
     * @param groupLinkId 群链接 ID
     * @param preferredCreatorAccountId 新群任务冻结的建群者账号；手动和链接模式为空
     */
    GroupCreatorLeaveResultVO execute(Long groupLinkId, Long preferredCreatorAccountId);
}
