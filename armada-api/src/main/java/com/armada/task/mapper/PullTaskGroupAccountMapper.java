package com.armada.task.mapper;

import com.armada.task.model.entity.PullTaskGroupAccount;
import com.armada.task.model.vo.PullTaskGroupAccountRoleCount;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 执行行角色账号数据访问层。 */
@Mapper
public interface PullTaskGroupAccountMapper {

    /**
     * 为执行行选中一个角色账号。
     *
     * <p>拉手行插入即取得跨任务占用：生成列 {@code occupancy_key} 在
     * {@code role_type=2 且 released_at IS NULL} 时取账号 ID，唯一键保证同一账号同时
     * 只服务一条执行行（ADR-0008）。冲突时抛
     * {@link org.springframework.dao.DuplicateKeyException}，调用方应把它当作
     * "该拉手已被占用"的预期路径处理，让本行进入等待拉手，不得当系统错误上抛。</p>
     *
     * @param row 角色账号；写入后回填 id
     * @return 新增行数
     */
    int insert(PullTaskGroupAccount row);

    /**
     * 读取执行行内某个角色的全部账号，按 roleSeq 升序。
     *
     * @param groupExecutionId 执行行 ID
     * @param roleType 角色，取值见 PullTaskGroupAccountRole
     * @return 角色账号列表
     */
    List<PullTaskGroupAccount> selectByExecutionAndRole(
            @Param("groupExecutionId") long groupExecutionId,
            @Param("roleType") int roleType);

    /**
     * 按角色统计执行行当前可用账号数，供详情页现算"当前数 / 计划数"。
     *
     * @param groupExecutionId 执行行 ID
     * @return 每个角色一行；没有可用账号的角色不出现在结果里
     */
    List<PullTaskGroupAccountRoleCount> countAvailableByRole(
            @Param("groupExecutionId") long groupExecutionId);

    /**
     * 释放单个拉手的跨任务占用。
     *
     * @param id 角色账号行 ID
     * @param now 释放时间(epoch 毫秒)
     * @return 实际释放行数
     */
    int releasePuller(@Param("id") long id, @Param("now") long now);

    /**
     * 执行行恢复时重新占用原拉手。
     *
     * <p>该账号已被其他执行行占走时抛
     * {@link org.springframework.dao.DuplicateKeyException}，这是"恢复时重新竞争拉手"
     * 的预期结果，调用方据此让本行继续等待。</p>
     *
     * @param id 角色账号行 ID
     * @param now 重新占用时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int reoccupyPuller(@Param("id") long id, @Param("now") long now);

    /**
     * 释放执行行下全部仍在占用中的拉手。
     *
     * <p>执行行完成、失败、被人工暂停或进入资源等待时调用。管理与站台角色不参与占用，
     * 不受影响。</p>
     *
     * @param groupExecutionId 执行行 ID
     * @param now 释放时间(epoch 毫秒)
     * @return 实际释放行数
     */
    int releaseAllPullersOfExecution(@Param("groupExecutionId") long groupExecutionId,
                                     @Param("now") long now);

    /**
     * 标记账号在本执行行不可用。
     *
     * @param id 角色账号行 ID
     * @param availabilityStatus 可用性，取值见 PullTaskGroupAccountAvailability
     * @param reasonCode 不可用原因码
     * @param cooldownUntil 风控冷却到期时间(epoch 毫秒)；非冷却场景传 null
     * @param now 更新时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int markUnavailable(@Param("id") long id,
                        @Param("availabilityStatus") int availabilityStatus,
                        @Param("reasonCode") String reasonCode,
                        @Param("cooldownUntil") Long cooldownUntil,
                        @Param("now") long now);

    /**
     * 回写账号的在群状态。
     *
     * @param id 角色账号行 ID
     * @param membershipStatus 在群状态，取值见 PullTaskGroupAccountMembershipStatus
     * @param joinedAt 确认在群时间(epoch 毫秒)；非成功场景传 null
     * @param now 更新时间(epoch 毫秒)
     * @return 实际更新行数
     */
    int updateMembership(@Param("id") long id,
                         @Param("membershipStatus") int membershipStatus,
                         @Param("joinedAt") Long joinedAt,
                         @Param("now") long now);
}
