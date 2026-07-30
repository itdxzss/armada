package com.armada.group.controller;

import com.armada.group.model.dto.HistoricalGroupParticipantActionDTO;
import com.armada.group.model.dto.HistoricalGroupRefreshDTO;
import com.armada.group.model.dto.HistoricalGroupQuery;
import com.armada.group.model.vo.HistoricalGroupDetailVO;
import com.armada.group.model.vo.HistoricalGroupItemVO;
import com.armada.group.model.vo.HistoricalGroupParticipantActionVO;
import com.armada.group.service.HistoricalGroupService;
import com.armada.group.service.impl.HistoricalGroupAccountGroupQueryService;
import com.armada.group.service.impl.HistoricalGroupAccountGroupRefreshService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号登录前历史群的列表与手动刷新端点。
 */
@RestController
@RequestMapping("/api/historical-groups")
@PreAuthorize("hasAuthority('tenant:historical_group:view')")
public class HistoricalGroupController {

    private final HistoricalGroupService historicalGroupService;
    private final HistoricalGroupAccountGroupQueryService queryService;
    private final HistoricalGroupAccountGroupRefreshService refreshService;

    /**
     * 创建历史群端点。
     *
     * @param historicalGroupService 历史群详情与成员操作服务
     * @param queryService 账号组历史群分页查询服务
     * @param refreshService 账号组历史群实时同步服务
     */
    public HistoricalGroupController(
            HistoricalGroupService historicalGroupService,
            HistoricalGroupAccountGroupQueryService queryService,
            HistoricalGroupAccountGroupRefreshService refreshService) {
        this.historicalGroupService = historicalGroupService;
        this.queryService = queryService;
        this.refreshService = refreshService;
    }

    /**
     * 读取账号组 baseline 历史群并集,不触发协议刷新。
     *
     * @param query 账号组和分页参数
     * @return 群维度分页结果
     */
    @GetMapping
    public ApiResponse<PageResult<HistoricalGroupItemVO>> list(
            @ModelAttribute HistoricalGroupQuery query) {
        return ApiResponse.ok(queryService.list(query));
    }

    /**
     * 手动同步账号组历史群的当前关系与摘要。
     *
     * @param dto 刷新请求
     * @return 成功响应;前端随后复用分页接口读取持久化结果
     */
    @PostMapping("/refresh")
    public ApiResponse<Void> refresh(@RequestBody HistoricalGroupRefreshDTO dto) {
        refreshService.refresh(dto == null ? null : dto.accountGroupId());
        return ApiResponse.ok();
    }

    /**
     * 用户打开单群详情时按需加载完整 metadata、成员和当前邀请链接。
     *
     * @param accountGroupId 来源账号组 ID
     * @param groupJid  baseline 群 JID
     * @return 单群实时详情及操作门禁状态
     */
    @GetMapping("/detail")
    public ApiResponse<HistoricalGroupDetailVO> detail(
            @RequestParam Long accountGroupId,
            @RequestParam String groupJid) {
        return ApiResponse.ok(historicalGroupService.getHistoricalGroupDetail(accountGroupId, groupJid));
    }

    /**
     * 使用后台自动选择的管理员批量提升历史群普通成员。
     *
     * @param dto 账号组、baseline 群和目标成员
     * @return 按请求顺序排列的逐成员结果
     */
    @PostMapping("/participants/promote")
    public ApiResponse<HistoricalGroupParticipantActionVO> promote(
            @RequestBody HistoricalGroupParticipantActionDTO dto) {
        return ApiResponse.ok(historicalGroupService.promoteParticipants(dto));
    }

    /**
     * 使用后台自动选择的管理员批量降级历史群内其他管理员。
     *
     * @param dto 账号组、baseline 群和目标成员
     * @return 按请求顺序排列的逐成员结果
     */
    @PostMapping("/participants/demote")
    public ApiResponse<HistoricalGroupParticipantActionVO> demote(
            @RequestBody HistoricalGroupParticipantActionDTO dto) {
        return ApiResponse.ok(historicalGroupService.demoteParticipants(dto));
    }

    /**
     * 使用后台自动选择的管理员批量移除历史群内可操作成员。
     *
     * @param dto 账号组、baseline 群和目标成员
     * @return 按请求顺序排列的逐成员结果
     */
    @PostMapping("/participants/remove")
    public ApiResponse<HistoricalGroupParticipantActionVO> remove(
            @RequestBody HistoricalGroupParticipantActionDTO dto) {
        return ApiResponse.ok(historicalGroupService.removeParticipants(dto));
    }
}
