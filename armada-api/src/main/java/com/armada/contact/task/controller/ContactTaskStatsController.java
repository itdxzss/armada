package com.armada.contact.task.controller;

import com.armada.contact.task.model.vo.ContactTaskStatsVO;
import com.armada.contact.task.service.ContactTaskStatsService;
import com.armada.shared.response.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 通讯录任务效果概览，沿用任务查看权限和租户归属。 */
@RestController
@RequestMapping("/api/contact-tasks")
@PreAuthorize("hasAuthority('tenant:contact_task:view')")
public class ContactTaskStatsController {
    private final ContactTaskStatsService service;
    /** 创建任务统计入口。 */
    public ContactTaskStatsController(ContactTaskStatsService service) { this.service = service; }
    /** 查询整条任务的处理进度、累计回执和异常原因，不受明细分页影响。 */
    @GetMapping("/{id}/stats")
    public ApiResponse<ContactTaskStatsVO> stats(@PathVariable Long id) {
        return ApiResponse.ok(service.stats(id));
    }
}
