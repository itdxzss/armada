package com.armada.group.controller;

import com.armada.group.model.dto.GroupCreateDTO;
import com.armada.group.model.vo.GroupCreateVO;
import com.armada.group.service.GroupOperationService;
import com.armada.shared.response.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WhatsApp 群真实操作端点。
 */
@RestController
@RequestMapping("/api/groups")
public class GroupController {

    private final GroupOperationService groupOperationService;

    public GroupController(GroupOperationService groupOperationService) {
        this.groupOperationService = groupOperationService;
    }

    /**
     * 创建 WhatsApp 群并添加初始成员。
     *
     * @param dto 创建请求
     * @return 新建群 JID 与逐成员结果
     */
    @PostMapping("/create")
    public ApiResponse<GroupCreateVO> create(@RequestBody GroupCreateDTO dto) {
        return ApiResponse.ok(groupOperationService.createGroup(dto));
    }
}
