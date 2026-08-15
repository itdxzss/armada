package com.armada.task.service;

import com.armada.task.model.dto.PullTaskGroupSettingsCallback;

/** 收敛拉群单项群设置协议结果。 */
public interface PullTaskGroupSettingsResultService {

    /**
     * 按 commandId 与 attemptNo 收敛一条群设置命令的结果。
     *
     * <p>阻断规则全部在本层：放开加人权限是阶段推进条件，失败即退避重试；关闭进群审核只写动作行，
     * 无论成败都不触碰执行行。协议层不参与该判断。</p>
     *
     * @param callback 协议结果
     * @return 结果被采纳返回 true；关联校验不通过返回 false
     */
    boolean apply(PullTaskGroupSettingsCallback callback);
}
