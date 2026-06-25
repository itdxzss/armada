package com.armada.account.controller;

import com.armada.account.model.dto.AccountGroupDTO;
import com.armada.account.model.dto.AccountGroupQuery;
import com.armada.account.model.dto.AccountIdsDTO;
import com.armada.account.model.vo.AccountGroupVO;
import com.armada.account.service.AccountGroupService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号分组 CRUD 端点。
 *
 * <p>Controller 只做参数接收、上下文衔接与响应组装,业务规则全部在 Service。</p>
 */
@RestController
@RequestMapping("/api/account-groups")
public class AccountGroupController {

    private final AccountGroupService service;

    public AccountGroupController(AccountGroupService service) {
        this.service = service;
    }

    /**
     * 账号分组列表(分页 + 关键字筛选)。
     * 同时懒创建系统默认分组(Service 层保证幂等)。
     *
     * @param query 查询条件
     * @return 分页分组列表
     */
    @GetMapping
    public ApiResponse<PageResult<AccountGroupVO>> list(@ModelAttribute AccountGroupQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    /**
     * 新建账号分组。
     *
     * @param dto 分组信息
     * @return 新建/复活的分组 VO
     */
    @PostMapping
    public ApiResponse<AccountGroupVO> create(@RequestBody AccountGroupDTO dto) {
        return ApiResponse.ok(service.create(dto));
    }

    /**
     * 修改账号分组基本信息(系统默认分组不可改名)。
     *
     * @param id  路径中的分组 ID
     * @param dto 更新内容
     * @return 空成功响应
     */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody AccountGroupDTO dto) {
        service.update(id, dto);
        return ApiResponse.ok();
    }

    /**
     * 批量软删除账号分组(全或无闸门:组内有账号或命中系统组则整批拒删)。
     *
     * @param request 分组 ID 列表
     * @return 实际删除数
     */
    @PostMapping("/batch-delete")
    public ApiResponse<Integer> batchDelete(@RequestBody AccountIdsDTO request) {
        return ApiResponse.ok(service.batchDelete(request.ids()));
    }
}
