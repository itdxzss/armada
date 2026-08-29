package com.armada.contact.task.mapper;

import com.armada.contact.task.model.entity.ContactFriendTaskAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 通讯录营销任务账号维度读模型的数据访问。 */
@Mapper
public interface ContactFriendTaskAccountMapper {

    /**
     * 分页查询任务的账号发送数据。
     *
     * @param taskId 任务 ID
     * @param sortBy 排序列，仅接受 needSendNum / sentNum / failNum，其余按 id
     * @param sortOrder 排序方向，asc 或 desc
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 当前页账号行
     */
    List<ContactFriendTaskAccount> selectPage(@Param("taskId") Long taskId,
                                              @Param("sortBy") String sortBy,
                                              @Param("sortOrder") String sortOrder,
                                              @Param("offset") int offset,
                                              @Param("limit") int limit);

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
     * 把已排干的账号行收敛为终态：发成功过至少一条为 DONE，一条都没成功为 FAILED。
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
