package com.armada.group.mapper;

import com.armada.group.model.vo.WhatsappGroupJoinFactVO;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** WhatsApp 群成员最近一次进群事实 Mapper。 */
@Mapper
public interface WhatsappGroupMemberJoinFactMapper {

    /** 按租户和群集合读取最近进群事实。 */
    @InterceptorIgnore(tenantLine = "true")
    List<WhatsappGroupJoinFactVO> selectByGroupJids(
            @Param("tenantId") Long tenantId,
            @Param("groupJids") List<String> groupJids);
}
