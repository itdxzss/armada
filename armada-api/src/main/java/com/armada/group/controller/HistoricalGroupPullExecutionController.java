package com.armada.group.controller;

import com.armada.group.model.dto.HistoricalGroupPullCreateForm;
import com.armada.group.model.vo.HistoricalGroupPullExecutionVO;
import com.armada.group.service.HistoricalGroupPullExecutionService;
import com.armada.shared.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 历史群单群拉人执行创建、启动与轮询端点。 */
@RestController
@RequestMapping("/api/historical-group-pull-executions")
public class HistoricalGroupPullExecutionController {

    private final HistoricalGroupPullExecutionService executionService;

    /**
     * 创建历史群拉人端点。
     *
     * @param executionService 待执行创建与查询服务
     */
    public HistoricalGroupPullExecutionController(HistoricalGroupPullExecutionService executionService) {
        this.executionService = executionService;
    }

    /**
     * 上传料子并创建幂等待执行记录；邀请链接只由后端实时详情取得。
     *
     * @param form multipart 文件与固定创建字段
     * @return 新建执行；重复幂等键返回原执行
     */
    @PostMapping
    public ApiResponse<HistoricalGroupPullExecutionVO> create(
            @ModelAttribute HistoricalGroupPullCreateForm form) {
        return ApiResponse.ok(executionService.create(form.toDTO(), form.getFile()));
    }

    /**
     * 重新校验服务端门禁并原子启动待执行任务。
     *
     * @param id 待启动执行 ID
     * @return 已进入运行态的执行详情
     */
    @PostMapping("/{id}/start")
    public ApiResponse<HistoricalGroupPullExecutionVO> start(@PathVariable Long id) {
        return ApiResponse.ok(executionService.start(id));
    }

    /**
     * 查询指定执行和全部逐成员结果。
     *
     * @param id 执行 ID
     * @return 执行详情
     */
    @GetMapping("/{id}")
    public ApiResponse<HistoricalGroupPullExecutionVO> getById(@PathVariable Long id) {
        return ApiResponse.ok(executionService.getById(id));
    }

    /**
     * 查询固定操作账号与群最近一次执行；没有历史执行时 data 为 null。
     *
     * @param accountId 操作账号 ID
     * @param groupJid  目标群 JID
     * @return 最近执行或空 data
     */
    @GetMapping("/latest")
    public ApiResponse<HistoricalGroupPullExecutionVO> latest(
            @RequestParam Long accountId,
            @RequestParam String groupJid) {
        return ApiResponse.ok(executionService.latest(accountId, groupJid).orElse(null));
    }
}
