package com.armada.contact.task.service;

import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactTaskStatsMapper;
import com.armada.contact.task.model.dto.ContactTaskRecipientQuery;
import com.armada.contact.task.model.vo.ContactTaskRecipientVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 沿用当前租户查询任务收件人明细。 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ContactTaskRecipientService {
    private final ContactFriendTaskMapper taskMapper;
    private final ContactTaskStatsMapper recipientMapper;
    /** 创建受任务可见性约束的明细查询服务。 */
    public ContactTaskRecipientService(ContactFriendTaskMapper taskMapper,
            ContactTaskStatsMapper recipientMapper) {
        this.taskMapper = taskMapper;
        this.recipientMapper = recipientMapper;
    }
    /** 原账号级接口委托统一筛选查询，保持分页参数兼容。 */
    public PageResult<ContactTaskRecipientVO> list(Long taskId, Long accountId, Integer page, Integer pageSize) {
        ContactTaskRecipientQuery query = new ContactTaskRecipientQuery();
        query.setTaskAccountId(accountId);
        query.setPage(page == null ? 1 : page);
        query.setPageSize(pageSize == null ? 20 : pageSize);
        return list(taskId, query);
    }

    /** 查询整个任务或指定账号的联系人，过滤、总数和分页在同一读快照内执行。 */
    public PageResult<ContactTaskRecipientVO> list(Long taskId, ContactTaskRecipientQuery query) {
        query.validate();
        if (taskMapper.selectById(taskId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "通讯录任务不存在");
        }
        if (query.getTaskAccountId() != null
                && recipientMapper.countAccountScope(taskId, query.getTaskAccountId()) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "任务账号不存在");
        }
        long total = recipientMapper.countRecipients(taskId, query);
        return PageResult.of(recipientMapper.selectRecipients(taskId, query),
                query.getPage(), query.getPageSize(), total);
    }
}
