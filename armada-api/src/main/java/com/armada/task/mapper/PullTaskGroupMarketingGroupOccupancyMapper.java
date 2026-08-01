package com.armada.task.mapper;

import com.armada.task.model.entity.PullTaskGroupMarketingGroupOccupancy;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 拉群营销单群软占用和硬占用数据访问层。 */
@Mapper
public interface PullTaskGroupMarketingGroupOccupancyMapper {

    /**
     * 新增软占用；同群已有有效占用时由数据库唯一键抛出冲突。
     *
     * @param row 待新增软占用
     * @return 新增成功行数
     */
    int insertWaiting(PullTaskGroupMarketingGroupOccupancy row);

    /**
     * 批量读取群组当前有效占用。
     *
     * @param groupJids 群 JID
     * @return 当前有效占用
     */
    List<PullTaskGroupMarketingGroupOccupancy> selectActiveByGroupJids(
            @Param("groupJids") List<String> groupJids);

    /**
     * 查询当前用户拥有的一个等待池。
     *
     * @param reservationToken 等待池随机标识
     * @param createdBy 当前登录用户 ID
     * @return 有效软占用行
     */
    List<PullTaskGroupMarketingGroupOccupancy> selectWaitingByToken(
            @Param("reservationToken") String reservationToken,
            @Param("createdBy") long createdBy);

    /**
     * 读取当前有效等待池创建人，用于校验 token 所有权。
     *
     * @param reservationToken 等待池标识
     * @return 当前有效等待池创建人；不存在时为空
     */
    Long selectCreatorByToken(@Param("reservationToken") String reservationToken);

    /**
     * 续租当前用户仍有效的等待池。
     *
     * @param reservationToken 等待池标识
     * @param createdBy 当前用户 ID
     * @param expiresAt 新过期时间
     * @param now 当前时间
     * @return 续租行数
     */
    int renewWaiting(
            @Param("reservationToken") String reservationToken,
            @Param("createdBy") long createdBy,
            @Param("expiresAt") long expiresAt,
            @Param("now") long now);

    /**
     * 同步当前表单里的任务名称和计划启动时间快照。
     *
     * @param reservationToken 等待池标识
     * @param createdBy 当前用户 ID
     * @param taskName 任务名称快照
     * @param plannedStartAt 计划启动时间
     * @param now 当前时间
     * @return 更新行数
     */
    int updateWaitingSnapshot(
            @Param("reservationToken") String reservationToken,
            @Param("createdBy") long createdBy,
            @Param("taskName") String taskName,
            @Param("plannedStartAt") Long plannedStartAt,
            @Param("now") long now);

    /**
     * 释放已超时的等待池软占用，防止浏览器异常退出形成永久占用。
     *
     * @param now 当前时间
     * @return 释放行数
     */
    int releaseExpiredWaiting(@Param("now") long now);

    /**
     * 释放当前用户在一个等待池中的单群软占用。
     *
     * @param reservationToken 等待池随机标识
     * @param groupJid 群 JID
     * @param createdBy 当前登录用户 ID
     * @param releasedAt 释放时间
     * @return 实际释放行数
     */
    int releaseWaiting(
            @Param("reservationToken") String reservationToken,
            @Param("groupJid") String groupJid,
            @Param("createdBy") long createdBy,
            @Param("releasedAt") long releasedAt);

    /**
     * 释放当前用户拥有的整个等待池。
     *
     * @param reservationToken 等待池标识
     * @param createdBy 当前用户 ID
     * @param releasedAt 释放时间
     * @return 实际释放行数
     */
    int releaseWaitingByToken(
            @Param("reservationToken") String reservationToken,
            @Param("createdBy") long createdBy,
            @Param("releasedAt") long releasedAt);
}
