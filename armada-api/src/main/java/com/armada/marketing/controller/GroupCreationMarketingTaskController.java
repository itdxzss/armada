package com.armada.marketing.controller;

import com.armada.marketing.model.dto.CreateGroupCreationMarketingTaskDTO;
import com.armada.marketing.model.dto.GroupCreationMarketingTaskQuery;
import com.armada.marketing.model.vo.GroupCreationMarketingAccountCandidate;
import com.armada.marketing.model.vo.GroupCreationMarketingTaskDetailVO;
import com.armada.marketing.model.vo.GroupCreationMarketingTaskVO;
import com.armada.marketing.service.GroupCreationMarketingTaskService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/group-creation-marketing-tasks")
public class GroupCreationMarketingTaskController {

    private final GroupCreationMarketingTaskService service;

    public GroupCreationMarketingTaskController(GroupCreationMarketingTaskService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<GroupCreationMarketingTaskVO>> list(@ModelAttribute GroupCreationMarketingTaskQuery query) {
        return ApiResponse.ok(service.listTasks(query));
    }

    @GetMapping("/account-candidates")
    public ApiResponse<List<GroupCreationMarketingAccountCandidate>> accountCandidates(@RequestParam Long accountGroupId) {
        return ApiResponse.ok(service.accountCandidates(accountGroupId));
    }

    @PostMapping
    public ApiResponse<GroupCreationMarketingTaskDetailVO> create(@RequestBody CreateGroupCreationMarketingTaskDTO request) {
        return ApiResponse.ok(service.createTask(request));
    }

    @PostMapping("/{id}/stop")
    public ApiResponse<Integer> stop(@PathVariable Long id) {
        return ApiResponse.ok(service.stopTask(id));
    }

    @GetMapping("/{id}")
    public ApiResponse<GroupCreationMarketingTaskDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.getDetail(id));
    }
}
