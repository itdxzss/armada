package com.armada.group.mapper;

import com.armada.group.model.dto.WhatsappGroupDepartureFact;
import com.armada.group.model.vo.WhatsappGroupDepartedMemberVO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** WhatsApp 群成员最近退群事实 Mapper。 */
@Mapper
public interface WhatsappGroupDepartedMemberMapper {

    /** 确保成员事实行存在，并单调补齐可信手机号别名。 */
    @InterceptorIgnore(tenantLine = "true")
    int upsertIdentity(@Param("fact") WhatsappGroupDepartureFact fact,
                       @Param("now") long now);

    /** 仅当参数事件按稳定仲裁键胜出时，整体替换最近退出事实。 */
    @InterceptorIgnore(tenantLine = "true")
    int updateIfNewer(@Param("fact") WhatsappGroupDepartureFact fact,
                      @Param("now") long now);

    /** 按租户和群集合读取最近退群事实。 */
    @InterceptorIgnore(tenantLine = "true")
    List<WhatsappGroupDepartedMemberVO> selectByGroupJids(
            @Param("tenantId") Long tenantId,
            @Param("groupJids") List<String> groupJids);
}
