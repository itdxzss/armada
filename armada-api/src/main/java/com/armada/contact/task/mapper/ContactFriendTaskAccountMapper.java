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
}
