package com.armada.contact.task.mapper;

import com.armada.contact.task.model.entity.ContactFriendTaskAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 通讯录营销任务账号维度读模型的数据访问。 */
@Mapper
public interface ContactFriendTaskAccountMapper {
    /** 只读取任务快照的发信账号 ID，用于校验回执归属；租户条件由插件注入。 */
    Long selectSenderAccountId(@Param("id") Long id);

    /** 读取本任务尚未固定收件人的账号；调用方须先锁定任务行。 */
    List<ContactFriendTaskAccount> selectPreparing(@Param("taskId") Long taskId);

    /** 统计仍在准备名单的账号，防止零收件人任务提前完成。 */
    long countPreparing(@Param("taskId") Long taskId);

    /** 把准备中的账号固化为待发送、失败或空名单，和收件人插入使用同一事务。 */
    int finishPreparation(ContactFriendTaskAccount row);
    /** 异常时停止该任务账号后续发送。 */
    int stopAccount(@Param("id") Long id, @Param("reason") String reason,
                    @Param("updatedAt") long updatedAt);


    /**
     * 统计任务下账号行总数。
     *
     * @param taskId 任务 ID
     * @return 总数
     */
    long countByTaskId(@Param("taskId") Long taskId);

    /**
     * 插入任务账号行并回填主键。展开收件人需要这个 ID。
     *
     * @param row 账号行
     * @return 受影响行数
     */
    int insert(ContactFriendTaskAccount row);

    /**
     * 按主键读取任务账号行。
     *
     * @param id 账号行 ID
     * @return 账号行，不存在时为 null
     */
    ContactFriendTaskAccount selectById(@Param("id") Long id);

    /**
     * 累加该账号成功条数。
     *
     * @param id 账号行 ID
     * @param updatedAt 更新时间（epoch 毫秒）
     * @return 受影响行数
     */
    int incrementSentNum(@Param("id") Long id, @Param("updatedAt") long updatedAt);

    /**
     * 累加该账号失败条数。
     *
     * @param id 账号行 ID
     * @param updatedAt 更新时间（epoch 毫秒）
     * @return 受影响行数
     */
    int incrementFailNum(@Param("id") Long id, @Param("updatedAt") long updatedAt);

    /**
     * 把账号行推进到执行中。仅 PENDING 行会被更新。
     *
     * @param id 账号行 ID
     * @param updatedAt 更新时间（epoch 毫秒）
     * @return 受影响行数
     */
    int markRunning(@Param("id") Long id, @Param("updatedAt") long updatedAt);

    /**
     * 收敛已排干的 PENDING/RUNNING 账号：已有成功或未知结果时为 DONE，否则为 FAILED。
     * 含 UNKNOWN 时保留账号状态快照；已明确停止为 FAILED 的账号不在本方法中恢复。
     *
     * @param taskId 任务 ID
     * @param updatedAt 更新时间（epoch 毫秒）
     * @return 受影响行数
     */
    int settleDrainedAccounts(@Param("taskId") Long taskId, @Param("updatedAt") long updatedAt);

    /**
     * 统计任务下收敛为 FAILED 的账号数，即 invalid_account_num 的口径。
     *
     * @param taskId 任务 ID
     * @return 失败账号数
     */
    long countFailedAccounts(@Param("taskId") Long taskId);
}
