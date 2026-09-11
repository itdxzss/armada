package com.armada.contact.task.service;

import com.armada.contact.task.model.dto.ContactTaskFormDTO;
import com.armada.contact.task.model.dto.ContactTaskQuery;
import com.armada.contact.task.model.vo.ContactTaskAccountItemVO;
import com.armada.contact.task.model.vo.ContactTaskDetailVO;
import com.armada.contact.task.model.vo.ContactTaskListItemVO;
import com.armada.shared.response.PageResult;
import java.util.List;

/** 通讯录营销任务业务服务。 */
public interface ContactTaskService {

    /**
     * 批量软删除当前租户的未开始、已完成或已停止任务，保留账号及收件人明细。
     *
     * <p>同一事务内按 ID 升序锁定并复查状态，任一任务不满足条件则整批回滚。</p>
     *
     * @param ids 待删除任务 ID，1 至 200 个，重复 ID 只处理一次
     * @return 实际删除任务数
     * @throws com.armada.shared.exception.BusinessException 参数非法、任务不可见或需先停止时抛出
     */
    int batchDelete(List<Long> ids);

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
     * 按筛选条件试算命中账号数，供任务抽屉的「账号范围」区块显示。
     *
     * <p>与启用时真正圈号走同一套归一化与同一份 SQL 条件；否则界面显示的数字会骗人。</p>
     *
     * @param accountFilterJson 前端提交的原始筛选 JSON，允许为 null 或非法
     * @return 命中账号数
     */
    int previewAccountCount(String accountFilterJson);

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
     * @param sortBy 排序列，支持计划、已处理、发送确认、送达、已读、失败、未知和跳过指标
     * @param sortOrder 排序方向 asc / desc
     * @param page 页码
     * @param pageSize 每页条数
     * @return 当前页账号发送数据
     */
    PageResult<ContactTaskAccountItemVO> accountData(
            Long id, String sortBy, String sortOrder, Integer page, Integer pageSize);
}
