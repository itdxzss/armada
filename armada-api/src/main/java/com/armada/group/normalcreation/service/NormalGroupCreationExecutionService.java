package com.armada.group.normalcreation.service;

import com.armada.group.normalcreation.model.dto.NormalGroupCreationCommand;

/** 新建普群三个 Kafka 阶段的执行边界。 */
public interface NormalGroupCreationExecutionService {

    /** 执行与命令 action 对应的幂等阶段。 */
    void execute(NormalGroupCreationCommand command);
}
