package com.armada.group.service;

import com.armada.group.mapper.GroupScriptCandidateMapper;
import com.armada.group.model.vo.GroupScriptCandidateVO;
import java.util.List;
import org.springframework.stereotype.Service;

/** 为剧本营销提供当前群资格，只读事实且不触发进群或上线。 */
@Service
public class GroupScriptCandidateService {
    private final GroupScriptCandidateMapper mapper;
    /** 注入 canonical 群关系查询。 */
    public GroupScriptCandidateService(GroupScriptCandidateMapper mapper) { this.mapper = mapper; }
    /** 批量读取至多 100 个已授权目标群；空集不放宽为全租户查询。 */
    public List<GroupScriptCandidateVO> list(List<Long> groupIds, Long accountGroupId, List<Long> adminIds) {
        if (groupIds == null || groupIds.isEmpty()) return List.of();
        return mapper.list(groupIds, accountGroupId, adminIds);
    }
}
