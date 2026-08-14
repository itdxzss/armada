package com.armada.group.service;

import com.armada.group.model.dto.GroupParticipantObservation;
import java.util.List;

/** 收敛协议群成员增量事实并同步本地群角色投影。 */
public interface GroupParticipantObservationService {

    /** 应用同一批协议观察；旧事实不会覆盖数据库中已经胜出的新事实。 */
    void apply(List<GroupParticipantObservation> observations);
}
