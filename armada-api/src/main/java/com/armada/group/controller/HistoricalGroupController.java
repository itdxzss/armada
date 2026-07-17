package com.armada.group.controller;

import com.armada.group.model.dto.HistoricalGroupParticipantActionDTO;
import com.armada.group.model.dto.HistoricalGroupRefreshDTO;
import com.armada.group.model.vo.HistoricalGroupDetailVO;
import com.armada.group.model.vo.HistoricalGroupItemVO;
import com.armada.group.model.vo.HistoricalGroupParticipantActionVO;
import com.armada.group.service.HistoricalGroupService;
import com.armada.shared.response.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
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
public class HistoricalGroupController {

    private final HistoricalGroupService historicalGroupService;

    /**
     * 创建历史群端点。
     *
     * @param historicalGroupService 历史群聚合服务
     */
    public HistoricalGroupController(HistoricalGroupService historicalGroupService) {
        this.historicalGroupService = historicalGroupService;
    }

    /**
     * 读取操作账号 baseline 历史群,不触发协议刷新。
     *
     * @param accountId 当前租户操作账号 ID
     * @return 按 baseline JID 顺序排列的历史群
     */
    @GetMapping
    public ApiResponse<List<HistoricalGroupItemVO>> list(@RequestParam Long accountId) {
        return ApiResponse.ok(historicalGroupService.listHistoricalGroups(accountId));
    }

    /**
     * 手动刷新操作账号历史群的当前关系与摘要。
     *
     * @param dto 刷新请求
     * @return 仅对本次请求有效的刷新结果
     */
    @PostMapping("/refresh")
    public ApiResponse<List<HistoricalGroupItemVO>> refresh(@RequestBody HistoricalGroupRefreshDTO dto) {
        Long accountId = dto == null ? null : dto.accountId();
        return ApiResponse.ok(historicalGroupService.refreshHistoricalGroups(accountId));
    }

    /**
     * 用户打开单群详情时按需加载完整 metadata、成员和当前邀请链接。
     *
     * @param accountId 固定操作账号 ID
     * @param groupJid  baseline 群 JID
     * @return 单群实时详情及操作门禁状态
     */
    @GetMapping("/detail")
    public ApiResponse<HistoricalGroupDetailVO> detail(
            @RequestParam Long accountId,
            @RequestParam String groupJid) {
        return ApiResponse.ok(historicalGroupService.getHistoricalGroupDetail(accountId, groupJid));
    }

    /**
     * 使用固定操作账号批量提升历史群普通成员。
     *
     * @param dto 固定账号、baseline 群和目标成员
     * @return 按请求顺序排列的逐成员结果
     */
    @PostMapping("/participants/promote")
    public ApiResponse<HistoricalGroupParticipantActionVO> promote(
            @RequestBody HistoricalGroupParticipantActionDTO dto) {
        return ApiResponse.ok(historicalGroupService.promoteParticipants(dto));
    }

    /**
     * 使用固定操作账号批量降级历史群内其他管理员。
     *
     * @param dto 固定账号、baseline 群和目标成员
     * @return 按请求顺序排列的逐成员结果
     */
    @PostMapping("/participants/demote")
    public ApiResponse<HistoricalGroupParticipantActionVO> demote(
            @RequestBody HistoricalGroupParticipantActionDTO dto) {
        return ApiResponse.ok(historicalGroupService.demoteParticipants(dto));
    }

    /**
     * 使用固定操作账号批量移除历史群内可操作成员。
     *
     * @param dto 固定账号、baseline 群和目标成员
     * @return 按请求顺序排列的逐成员结果
     */
    @PostMapping("/participants/remove")
    public ApiResponse<HistoricalGroupParticipantActionVO> remove(
            @RequestBody HistoricalGroupParticipantActionDTO dto) {
        return ApiResponse.ok(historicalGroupService.removeParticipants(dto));
    }
}
