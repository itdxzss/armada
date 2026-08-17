package com.armada.group.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 群链接健康状态数据访问。 */
@Mapper
public interface GroupLinkHealthMapper {

    /**
     * 批量查询不允许刷新群邀请链接的群入口 ID。
     *
     * <p>直接读取新群资料/邀请健康事实，与群组列表"群状态"列共用口径：封禁和不可用均拦截；
     * 链接失效(health_status=2)恰恰最需要刷新链接，必须放行。保证前端置灰的群与后端拒绝的群一致。</p>
     *
     * @param groupLinkIds 群入口 ID
     * @return 其中状态异常、不允许刷新链接的群入口 ID
     */
    List<Long> selectLinkRefreshBlockedIds(@Param("groupLinkIds") List<Long> groupLinkIds);

}
