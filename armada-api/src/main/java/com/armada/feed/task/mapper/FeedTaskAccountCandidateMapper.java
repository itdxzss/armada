package com.armada.feed.task.mapper;

import com.armada.account.selection.model.SelectedAccount;
import com.armada.feed.task.model.dto.FeedTaskCandidateQuery;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** 动态发布任务发送账号候选查询。 */
@Mapper
public interface FeedTaskAccountCandidateMapper {

    /** 按前端账号列表同口径筛选尚未加入任务的可发布账号。 */
    List<SelectedAccount> selectCandidates(FeedTaskCandidateQuery query);
}
