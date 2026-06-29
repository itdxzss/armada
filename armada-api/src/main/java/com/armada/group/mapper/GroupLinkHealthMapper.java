package com.armada.group.mapper;

import com.armada.group.model.entity.GroupLinkHealth;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 群链接健康状态数据访问。 */
@Mapper
public interface GroupLinkHealthMapper {

    /**
     * 通用健康状态 upsert。
     *
     * <p>用于明确拿到完整健康检测结果的场景;传入字段会覆盖旧值。</p>
     *
     * @param row 健康状态行
     * @return 影响行数
     */
    int upsert(GroupLinkHealth row);

    /**
     * 账号当前群同步来源的健康状态 upsert。
     *
     * <p>账号群列表事件能证明账号当前仍在群内,但协议层可能不返回成员数。
     * 此时保留旧的 current_count,避免清空健康巡检或历史预览得到的人数。</p>
     *
     * @param row 健康状态行
     * @return 影响行数
     */
    int upsertFromAccountGroupSync(GroupLinkHealth row);

    /**
     * 按群入口 ID 查询健康状态。
     *
     * @param groupLinkId 群入口 ID
     * @return 健康状态行;不存在时返回 null
     */
    GroupLinkHealth selectByGroupLinkId(@Param("groupLinkId") Long groupLinkId);
}
