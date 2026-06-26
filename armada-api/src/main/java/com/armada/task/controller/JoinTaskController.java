package com.armada.task.controller;

import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import com.armada.task.model.dto.CreateJoinTaskRequest;
import com.armada.task.model.dto.JoinTaskIdsDTO;
import com.armada.task.model.dto.JoinTaskQuery;
import com.armada.task.model.vo.JoinResultRowVO;
import com.armada.task.model.vo.JoinTaskDetailVO;
import com.armada.task.model.vo.JoinTaskVO;
import com.armada.task.service.JoinTaskService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 进群任务 CRUD 端点(列表/间隔/建/详情/编辑/明细/批量删)。
 *
 * <p>Controller 只做参数接收、上下文衔接与响应组装,业务规则全部在 Service。</p>
 */
@RestController
@RequestMapping("/api/join-tasks")
public class JoinTaskController {

    private final JoinTaskService service;

    public JoinTaskController(JoinTaskService service) {
        this.service = service;
    }

    /**
     * 进群任务列表(分页 + 关键字/状态/分组/分配方式/间隔/时间筛选)。
     *
     * @param query 查询条件
     * @return 分页任务列表
     */
    @GetMapping
    public ApiResponse<PageResult<JoinTaskVO>> list(@ModelAttribute JoinTaskQuery query) {
        return ApiResponse.ok(service.listTasks(query));
    }

    /**
     * 进群间隔下拉选项(去重)。
     *
     * @return 间隔标签列表
     */
    @GetMapping("/intervals")
    public ApiResponse<List<String>> intervals() {
        return ApiResponse.ok(service.intervalOptions());
    }

    /**
     * 新建进群任务。
     *
     * @param req 建任务入参
     * @return 新建任务列表行视图
     */
    @PostMapping
    public ApiResponse<JoinTaskVO> create(@RequestBody CreateJoinTaskRequest req) {
        return ApiResponse.ok(service.createTask(req));
    }

    /**
     * 任务详情(含 JSON 快照解析,供编辑回填)。
     *
     * @param id 任务 ID
     * @return 任务详情
     */
    @GetMapping("/{id}")
    public ApiResponse<JoinTaskDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(service.getDetail(id));
    }

    /**
     * 编辑任务(仅 DRAFT 且未执行)。
     *
     * @param id  任务 ID
     * @param req 新配置
     * @return 编辑后的任务详情
     */
    @PutMapping("/{id}")
    public ApiResponse<JoinTaskDetailVO> update(@PathVariable Long id, @RequestBody CreateJoinTaskRequest req) {
        return ApiResponse.ok(service.updateTask(id, req));
    }

    /**
     * 任务明细行(每账号每链接一行)。
     *
     * @param id 任务 ID
     * @return 明细行列表
     */
    @GetMapping("/{id}/results")
    public ApiResponse<List<JoinResultRowVO>> results(@PathVariable Long id) {
        return ApiResponse.ok(service.results(id));
    }

    /**
     * 批量软删任务。
     *
     * @param body 任务 id 列表
     * @return 实际软删行数
     */
    @PostMapping("/batch-delete")
    public ApiResponse<Integer> batchDelete(@RequestBody JoinTaskIdsDTO body) {
        return ApiResponse.ok(service.batchDelete(body.ids()));
    }
}
