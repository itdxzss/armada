package com.armada.contact.task.service;

import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.model.vo.ContactTaskRecipientVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import org.springframework.stereotype.Service;

/** 沿用当前租户查询任务收件人明细。 */
@Service
public class ContactTaskRecipientService {
    private final ContactFriendTaskMapper taskMapper;
    private final ContactFriendTaskRecipientMapper recipientMapper;
    public ContactTaskRecipientService(ContactFriendTaskMapper taskMapper,
            ContactFriendTaskRecipientMapper recipientMapper) {
        this.taskMapper = taskMapper;
        this.recipientMapper = recipientMapper;
    }
    public PageResult<ContactTaskRecipientVO> list(Long taskId, Long accountId, Integer page, Integer pageSize) {
        if (taskMapper.selectById(taskId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "通讯录任务不存在");
        }
        int current = page == null ? 1 : Math.max(1, page);
        int size = pageSize == null ? 20 : Math.min(200, Math.max(1, pageSize));
        long total = recipientMapper.countByAccount(taskId, accountId);
        var rows = recipientMapper.selectPage(taskId, accountId, (long) (current - 1) * size, size)
                .stream().map(ContactTaskRecipientVO::from).toList();
        return PageResult.of(rows, current, size, total);
    }
}
