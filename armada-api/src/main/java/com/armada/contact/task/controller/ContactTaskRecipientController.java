package com.armada.contact.task.controller;

import com.armada.contact.task.model.vo.ContactTaskRecipientVO;
import com.armada.contact.task.model.dto.ContactTaskRecipientQuery;
import com.armada.contact.task.service.ContactTaskRecipientService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 通讯录发送明细入口；权限与任务数据保持一致。 */
@RestController
@RequestMapping("/api/contact-tasks")
@PreAuthorize("hasAuthority('tenant:contact_task:view')")
public class ContactTaskRecipientController {
    private final ContactTaskRecipientService service;
    public ContactTaskRecipientController(ContactTaskRecipientService service) { this.service = service; }

    @GetMapping("/{id}/accounts/{taskAccountId}/recipients")
    public ApiResponse<PageResult<ContactTaskRecipientVO>> list(@PathVariable Long id,
            @PathVariable Long taskAccountId, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        return ApiResponse.ok(service.list(id, taskAccountId, page, pageSize));
    }

    /** 按任务查询联系人，可按账号、处理状态、累计或互斥回执状态过滤。 */
    @GetMapping("/{id}/recipients")
    public ApiResponse<PageResult<ContactTaskRecipientVO>> listTask(@PathVariable Long id,
            @ModelAttribute ContactTaskRecipientQuery query) {
        return ApiResponse.ok(service.list(id, query));
    }
}
