package com.armada.task.scheduler;

import com.armada.platform.protocol.model.result.CreatorDeletionObservation;
import com.armada.platform.protocol.model.result.CreatorDeletionResult;
import com.armada.platform.protocol.util.WhatsappJids;
import com.armada.task.model.entity.PullTaskCreatorDeletion;
import java.util.Objects;

/** 永久注销不可放宽的实时证据谓词；缺字段、旧缓存或查询失败一律不通过。 */
final class PullTaskCreatorDeletionProof {
    private PullTaskCreatorDeletionProof() { }

    static boolean takeover(PullTaskCreatorDeletionWork work, CreatorDeletionObservation proof, long now) {
        return freshManager(work, proof, now) && proof.creatorPresent()
                && !Objects.equals(work.manager().armadaAccountId(), work.creator().armadaAccountId())
                && !sameIdentity(work.manager().wsPhone(), work.creator().wsPhone())
                && (sameIdentity(proof.creator(), work.creator().wsPhone())
                    || sameIdentity(proof.creatorPN(), work.creator().wsPhone()));
    }

    static boolean cleaned(PullTaskCreatorDeletionWork work, CreatorDeletionObservation proof, long now) {
        return freshManager(work, proof, now) && proof.registrationKnown() && !proof.registered()
                && proof.creatorCleared() && work.deletion().getCreationBefore() != null
                && proof.creation() == work.deletion().getCreationBefore();
    }

    /** 返回缺失证明的类别，绝不回显号码、群 JID 或协议字段原值。 */
    static String pendingReason(PullTaskCreatorDeletionWork work, CreatorDeletionObservation proof, long now) {
        if (proof == null) { return "实时群与注册状态查询尚未完成或不可用"; }
        java.util.List<String> reasons = new java.util.ArrayList<>();
        if (!proof.structureComplete() || proof.creator() == null || proof.creatorPN() == null) {
            reasons.add("协议证明结构异常");
        }
        if (!Objects.equals(work.execution().getGroupJid(), proof.groupJid())) { reasons.add("核验群身份不匹配"); }
        if (proof.queriedAt() < now) { reasons.add("协议证明不新鲜"); }
        if (proof.creation() <= 0 || work.deletion().getCreationBefore() == null
                || proof.creation() != work.deletion().getCreationBefore()) { reasons.add("群创建时间与注销前不一致"); }
        if (!proof.managerPresent() || !proof.managerAdmin()) { reasons.add("接管管理号不在群或管理员权限未确认"); }
        if (!proof.registrationKnown()) { reasons.add("原建群号注册状态未确认"); }
        else if (proof.registered()) { reasons.add("原建群号仍注册"); }
        if (proof.creatorPresent()) { reasons.add("原建群者仍在群成员中"); }
        if (hasCreatorField(proof)) { reasons.add("Creator 或 CreatorPN 尚未清空"); }
        return String.join("；", reasons);
    }

    private static boolean hasCreatorField(CreatorDeletionObservation proof) {
        return proof.creator() != null && !proof.creator().isEmpty()
                || proof.creatorPN() != null && !proof.creatorPN().isEmpty();
    }

    static boolean matches(PullTaskCreatorDeletion row, CreatorDeletionResult result) {
        return result != null && Objects.equals(row.getOperationId(), result.operationId())
                && Objects.equals(row.getCreatorIdentityHash(), result.accountHash());
    }

    private static boolean freshManager(PullTaskCreatorDeletionWork work,
            CreatorDeletionObservation proof, long now) {
        return proof != null && proof.structureComplete() && proof.creation() > 0
                && proof.queriedAt() >= now && proof.managerPresent() && proof.managerAdmin()
                && Objects.equals(work.execution().getGroupJid(), proof.groupJid());
    }

    private static boolean sameIdentity(String value, String phone) {
        if (value == null || value.isBlank()) { return false; }
        try { return WhatsappJids.userJid(value).equals(WhatsappJids.userJid(phone)); }
        catch (IllegalArgumentException exception) { return false; }
    }
}
