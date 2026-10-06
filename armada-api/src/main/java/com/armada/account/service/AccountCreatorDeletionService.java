package com.armada.account.service;
import com.armada.account.model.dto.CreatorReservationRequest;
import com.armada.account.model.dto.CreatorDeletionBinding;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import java.util.Optional;
/** 一次性建群账号的跨任务排他占用与永久注销生命周期。 */
public interface AccountCreatorDeletionService {
    /** 创建/编辑配置时确认所选分组含支持注销的 Android 主设备身份。 */
    void validateCreatorGroup(Long groupId);
    /** 原子预留；其它任务、角色或注销记录占用时返回 false。 */
    boolean reserve(CreatorReservationRequest request);
    /** 返回当前租户本执行行已冻结且尚未注销的账号引用。 */
    Optional<ProtocolAccountRef> findReservedCreator(long executionId);
    /** 计算规范化账号身份哈希，不记录号码。 */
    String identityHash(ProtocolAccountRef creator);
    /** 复核身份与其它活动依赖后永久封锁自动上线和其它业务。 */
    boolean beginDeletion(CreatorDeletionBinding binding, long now);
    /** 放行证据已齐全时标记已注销；保留历史关系和审计。 */
    void completeDeletion(CreatorDeletionBinding binding, long now);
}
