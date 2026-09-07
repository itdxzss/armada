package com.armada.marketing.controller;

import com.armada.account.model.dto.AccountQuery;
import com.armada.account.model.vo.AccountListVO;
import com.armada.account.service.AccountService;
import com.armada.group.model.dto.GroupLinkQuery;
import com.armada.group.model.vo.GroupLinkVO;
import com.armada.group.service.GroupLinkService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 新页面使用既有租户账号与群分页服务，不依赖旧营销页面权限。 */
@RestController
@RequestMapping("/api/script-marketing-tasks/options")
@PreAuthorize("hasAuthority('tenant:script_marketing:view')")
public class ScriptMarketingOptionController {
    private final AccountService accounts;
    private final GroupLinkService groups;
    /** 注入跨业务域的公开服务。 */
    public ScriptMarketingOptionController(AccountService accounts, GroupLinkService groups) {
        this.accounts = accounts; this.groups = groups;
    }
    /** 查找本租户可见账号。 */
    @GetMapping("/accounts")
    public ApiResponse<PageResult<AccountListVO>> accounts(@ModelAttribute AccountQuery query) {
        return ApiResponse.ok(accounts.listAccounts(query));
    }
    /** 查找本租户可见目标群。 */
    @GetMapping("/groups")
    public ApiResponse<PageResult<GroupLinkVO>> groups(@ModelAttribute GroupLinkQuery query) {
        return ApiResponse.ok(groups.listByLabel(query));
    }
}
