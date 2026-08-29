package com.armada.contact.task.service;

import com.armada.contact.task.model.dto.ContactTaskFormDTO;
import com.armada.contact.task.model.dto.ContactTaskQuery;
import com.armada.contact.task.model.vo.ContactTaskAccountItemVO;
import com.armada.contact.task.model.vo.ContactTaskDetailVO;
import com.armada.contact.task.model.vo.ContactTaskListItemVO;
import com.armada.shared.response.PageResult;

/** 通讯录营销任务业务服务。 */
public interface ContactTaskService {

    /**
     * 分页查询当前租户任务。
     *
     * @param query 名称、状态、创建时间与分页条件
     * @return 当前页任务列表
     */
    PageResult<ContactTaskListItemVO> list(ContactTaskQuery query);

    /**
     * 查询任务完整详情。
     *
     * @param id 任务 ID
     * @return 任务详情
     */
    ContactTaskDetailVO detail(Long id);

    /**
     * 创建任务。创建后运行状态恒为未开始。
     *
     * @param form 任务表单
     * @param createdBy 创建人 user_id
     * @return 创建后的任务详情
     */
    ContactTaskDetailVO create(ContactTaskFormDTO form, Long createdBy);

    /**
     * 编辑任务。仅未开始任务允许编辑，消息类型一律不可改。
     *
     * @param id 任务 ID
     * @param form 任务表单
     * @return 编辑后的任务详情
     */
    ContactTaskDetailVO update(Long id, ContactTaskFormDTO form);

    /**
     * 执行任务动作：start / pause / resume / stop。
     *
     * @param id 任务 ID
     * @param action 动作名
     */
    void action(Long id, String action);

    /**
     * 分页查询任务的账号发送数据。
     *
     * @param id 任务 ID
     * @param sortBy 排序列，仅接受 needSendNum / sentNum / failNum
     * @param sortOrder 排序方向 asc / desc
     * @param page 页码
     * @param pageSize 每页条数
     * @return 当前页账号发送数据
     */
    PageResult<ContactTaskAccountItemVO> accountData(
            Long id, String sortBy, String sortOrder, Integer page, Integer pageSize);
}
