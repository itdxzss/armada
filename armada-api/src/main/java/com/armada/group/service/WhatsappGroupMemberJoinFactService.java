package com.armada.group.service;

import com.armada.group.model.dto.WhatsappGroupJoinFact;
import com.armada.group.model.vo.WhatsappGroupJoinFactVO;
import java.util.List;
import java.util.Optional;

/** WhatsApp 群成员进群事实业务边界。 */
public interface WhatsappGroupMemberJoinFactService {

    /** 幂等保存一批协议进群事实。 */
    void saveLatest(List<WhatsappGroupJoinFact> facts);

    /** 按租户和群集合读取协议已经提供的最近进群事实。 */
    List<WhatsappGroupJoinFactVO> findByGroupJids(Long tenantId, List<String> groupJids);

    /** 按可信号码和时间窗读取单个本地 ADD 事实，不发起协议名单查询。 */
    Optional<WhatsappGroupJoinFactVO> findRecentJoin(
            Long tenantId, String groupJid, String phone, long since, long until);
}
