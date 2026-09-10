package com.armada.group.mapper;

import com.armada.group.model.vo.GroupScriptCandidateVO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 按所选群和账号池批量读取当前群关系，复用 canonical 群事实，不读取进群任务状态。 */
@Mapper
public interface GroupScriptCandidateMapper {
    /** 当前租户所选分组及显式管理员的群关系；不存在的关系不伪造为已进群。 */
    List<GroupScriptCandidateVO> list(@Param("groupIds") List<Long> groupIds,
            @Param("accountGroupId") Long accountGroupId, @Param("adminIds") List<Long> adminIds);
}
