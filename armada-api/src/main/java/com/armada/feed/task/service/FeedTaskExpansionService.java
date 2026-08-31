package com.armada.feed.task.service;

import com.armada.account.selection.model.SelectedAccount;
import com.armada.feed.task.mapper.FeedTaskAccountMapper;
import com.armada.feed.task.model.entity.FeedTask;
import com.armada.feed.task.model.entity.FeedTaskAccount;
import org.springframework.stereotype.Service;

import java.util.List;

/** 动态发布任务账号展开服务。 */
@Service
public class FeedTaskExpansionService {

    public static final String SEND_STATUS_PENDING = "pending";

    private final FeedTaskAccountSelector selector;
    private final FeedTaskAccountMapper accountMapper;

    public FeedTaskExpansionService(FeedTaskAccountSelector selector, FeedTaskAccountMapper accountMapper) {
        this.selector = selector;
        this.accountMapper = accountMapper;
    }

    /** 展开一批新命中的发送账号，返回真实新增行数。 */
    public int expand(FeedTask task, int limit, long now) {
        List<SelectedAccount> candidates = selector.selectCandidates(task.getAccountFilter(), task.getId(), limit);
        int inserted = 0;
        for (SelectedAccount account : candidates) {
            FeedTaskAccount row = new FeedTaskAccount();
            row.setTenantId(task.getTenantId());
            row.setTaskId(task.getId());
            row.setAccountId(account.accountId());
            row.setAccountPhoneSnapshot(account.wsPhone());
            row.setSendStatus(SEND_STATUS_PENDING);
            row.setRetryNum(0);
            row.setRetryMax(task.getRetryMax());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            inserted += accountMapper.insert(row);
        }
        return inserted;
    }
}
