package com.armada.group.mapper;

import com.armada.group.model.vo.WhatsappGroupDepartedMemberVO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** WhatsApp 群成员最近退群事实 Mapper。 */
@Mapper
public interface WhatsappGroupDepartedMemberMapper {

    /** 按租户和群集合读取最近退群事实。 */
    @InterceptorIgnore(tenantLine = "true")
    List<WhatsappGroupDepartedMemberVO> selectByGroupJids(
            @Param("tenantId") Long tenantId,
            @Param("groupJids") List<String> groupJids);
}
