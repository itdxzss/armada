package com.armada.group.service;

import com.armada.group.model.dto.ControlledAccountGroupTransition;
import com.armada.group.model.dto.GroupParticipantObservation;
import java.util.List;

/** 收敛协议群成员增量事实并同步本地群角色投影。 */
public interface GroupParticipantObservationService {

    /** 应用同一批协议观察；旧事实不会覆盖数据库中已经胜出的新事实。 */
    void apply(List<GroupParticipantObservation> observations);

    /**
     * 按成员身份把库中已经胜出的成员事实收敛到受控账号的群关系上。
     *
     * <p>{@link #apply} 内部会自动做这一步。单独暴露是给 add/remove 这类不走观察路径、
     * 直接写进群/退群事实的增量链路用：成员事实已经落库，但受控账号自己的群关系还停在旧值，
     * 必须按当前胜出事实再对齐一次，否则受控号进退群后选号仍按旧关系走。</p>
     *
     * @param tenantId 租户 ID
     * @param groupJid 群 JID
     * @param participantJids 本次变更涉及的成员身份候选；库中按 PN 优先形态匹配，
     *                        因此同一个人的 PN 与 LID 两种形态都传进来，匹配不上的自动忽略
     * @return 本次真正从非在群转为在群的受控账号关系；没有变化时返回空列表
     */
    List<ControlledAccountGroupTransition> reconcileControlledMemberships(
            Long tenantId,
            String groupJid,
            List<String> participantJids);

    /**
     * 在通用 add 事实落库前，把事件中可识别的受控账号收敛为在群关系。
     *
     * <p>必须在普通成员 add 写入前调用，否则 self 成员行已是在群状态，
     * 无法再判断本次是否真正从非在群转为在群。</p>
     */
    List<ControlledAccountGroupTransition> reconcileControlledJoins(
            Long tenantId,
            String groupJid,
            List<String> participantJids,
            long observedAt,
            String sourceEventId);
}
