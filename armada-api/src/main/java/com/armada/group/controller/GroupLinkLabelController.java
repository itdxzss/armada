package com.armada.group.controller;

import com.armada.group.model.dto.GroupIdsDTO;
import com.armada.group.model.dto.GroupLinkLabelDTO;
import com.armada.group.model.dto.GroupLinkLabelQuery;
import com.armada.group.model.vo.GroupLinkLabelVO;
import com.armada.group.service.GroupLinkLabelService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * WS链接分组 CRUD 端点(A1-A4)。
 *
 * <p>Controller 只做参数接收、上下文衔接与响应组装,业务规则全部在 Service。</p>
 */
@RestController
@RequestMapping("/api/group-link-labels")
public class GroupLinkLabelController {

    private final GroupLinkLabelService service;

    public GroupLinkLabelController(GroupLinkLabelService service) {
        this.service = service;
    }

    /**
     * A1 分组列表(分页 + 关键字筛选)。
     *
     * @param query 查询条件
     * @return 分页分组列表
     */
    @GetMapping
    public ApiResponse<PageResult<GroupLinkLabelVO>> list(@ModelAttribute GroupLinkLabelQuery query) {
        return ApiResponse.ok(service.list(query));
    }

    /**
     * A2 新建分组。
     *
     * @param dto 分组信息
     * @return 新建/复活的分组 VO
     */
    @PostMapping
    public ApiResponse<GroupLinkLabelVO> create(@RequestBody GroupLinkLabelDTO dto) {
        return ApiResponse.ok(service.create(dto));
    }

    /**
     * A3 修改分组。
     *
     * @param id  路径中的分组 ID
     * @param dto 更新内容
     * @return 空成功响应
     */
    @PatchMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody GroupLinkLabelDTO dto) {
        service.update(id, dto);
        return ApiResponse.ok();
    }

    /**
     * A4 批量删除分组(级联软删群链接+导入批次)。
     *
     * @param request 分组 ID 列表
     * @return 实际删除数
     */
    @PostMapping("/batch-delete")
    public ApiResponse<Integer> batchDelete(@RequestBody GroupIdsDTO request) {
        return ApiResponse.ok(service.batchDelete(request.ids()));
    }
}
