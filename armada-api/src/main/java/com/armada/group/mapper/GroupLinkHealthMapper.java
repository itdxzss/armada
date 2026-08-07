package com.armada.group.mapper;

import com.armada.group.model.entity.GroupLinkHealth;
import java.util.List;
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
     * <p>账号群列表事件能证明账号当前仍可见该群，但不能证明 WhatsApp 已解除封禁。
     * 已封禁行保留状态、原因和失败计数；成员数为空时保留旧值。</p>
     *
     * @param row 健康状态行
     * @return 影响行数
     */
    int upsertFromAccountGroupSync(GroupLinkHealth row);

    /**
     * 批量查询已存在的群健康状态主键。
     *
     * <p>使用普通一致性读区分存量行与新增行，避免在 RR 下先对不存在的唯一键执行 UPDATE
     * 并持有 gap/supremum 锁。</p>
     *
     * @param groupLinkIds 群入口 ID
     * @return 已存在健康状态的群入口 ID
     */
    List<Long> selectExistingGroupLinkIds(@Param("groupLinkIds") List<Long> groupLinkIds);

    /**
     * 更新账号群同步已存在的健康状态，避免存量行进入自增 INSERT 候选锁路径。
     * 已封禁行只刷新成员数和观测时间，不清除封禁事实。
     *
     * @param row 健康状态行
     * @return 影响行数；不存在时返回 0，由调用方执行原子 upsert 兜底
     */
    int updateFromAccountGroupSync(GroupLinkHealth row);

    /**
     * 按群入口 ID 查询健康状态。
     *
     * @param groupLinkId 群入口 ID
     * @return 健康状态行;不存在时返回 null
     */
    GroupLinkHealth selectByGroupLinkId(@Param("groupLinkId") Long groupLinkId);
}
