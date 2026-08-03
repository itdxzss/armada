package com.armada.group.service;

import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import com.armada.group.model.vo.WhatsappGroupDepartedMemberVO;
import java.util.List;

/** WhatsApp 群成员退群事实业务边界。 */
public interface WhatsappGroupDepartedMemberService {

    /** 幂等保存一批协议退群事实。 */
    void saveLatest(List<WhatsappGroupDepartureFact> facts);

    /** 按租户和群集合读取协议已经提供的退群事实。 */
    List<WhatsappGroupDepartedMemberVO> findByGroupJids(Long tenantId, List<String> groupJids);
}
