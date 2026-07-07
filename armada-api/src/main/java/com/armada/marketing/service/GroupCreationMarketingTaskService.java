package com.armada.marketing.service;

import com.armada.marketing.model.dto.CreateGroupCreationMarketingTaskDTO;
import com.armada.marketing.model.dto.GroupCreationMarketingTaskQuery;
import com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate;
import com.armada.marketing.model.vo.GroupCreationMarketingTaskDetailVO;
import com.armada.marketing.model.vo.GroupCreationMarketingTaskVO;
import com.armada.shared.response.PageResult;
import java.util.List;

public interface GroupCreationMarketingTaskService {

    GroupCreationMarketingTaskDetailVO createTask(CreateGroupCreationMarketingTaskDTO request);

    PageResult<GroupCreationMarketingTaskVO> listTasks(GroupCreationMarketingTaskQuery query);

    GroupCreationMarketingTaskDetailVO getDetail(Long id);

    List<GroupCreationMarketingAccountCandidate> accountCandidates(Long accountGroupId);

    int stopTask(Long id);
}
