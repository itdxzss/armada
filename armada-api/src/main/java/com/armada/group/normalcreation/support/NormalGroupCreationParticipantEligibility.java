package com.armada.group.normalcreation.support;

import com.armada.group.normalcreation.model.NormalGroupCreationRecords.MemberWork;
import com.armada.group.normalcreation.model.NormalGroupCreationRecords.SecondaryAdminWork;

/** 新建普群参与者是否已与群主完成双向联系人准备。 */
public final class NormalGroupCreationParticipantEligibility {

    private NormalGroupCreationParticipantEligibility() {
    }

    /** 普通成员只有双向联系人操作都成功后才进入建群名单。 */
    public static boolean memberHasMutualCreatorContact(MemberWork member) {
        return member != null
                && "SUCCESS".equals(member.creatorSavedMemberStatus())
                && "SUCCESS".equals(member.memberSavedCreatorStatus());
    }

    /** 次管理员只有与群主双向联系人操作都成功后才进入建群名单。 */
    public static boolean secondaryAdminHasMutualCreatorContact(
            SecondaryAdminWork secondaryAdmin) {
        return secondaryAdmin != null
                && "SUCCESS".equals(secondaryAdmin.creatorSavedSecondaryStatus())
                && "SUCCESS".equals(secondaryAdmin.secondarySavedCreatorStatus());
    }
}
