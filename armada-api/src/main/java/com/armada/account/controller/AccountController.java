package com.armada.account.controller;

import com.armada.account.model.dto.AccountGroupDTO;
import com.armada.account.model.dto.AccountIdsDTO;
import com.armada.account.model.dto.AccountMigrateGroupDTO;
import com.armada.account.model.dto.AccountQuery;
import com.armada.account.model.vo.AccountBatchOnlineVO;
import com.armada.account.model.vo.AccountListVO;
import com.armada.account.model.vo.AccountOnlineVO;
import com.armada.account.model.vo.AccountProbeVO;
import com.armada.account.model.vo.AccountStatsVO;
import com.armada.account.model.vo.AccountStatusVO;
import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountLifecycleCommandService;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.account.service.AccountService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号列表端点(账号列表菜单)。
 *
 * <p>Controller 只做参数接收、上下文衔接与响应组装,业务规则全部在 Service。</p>
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;
    private final AccountGroupService accountGroupService;
    private final AccountOnlineCommandService accountOnlineCommandService;
    private final AccountLifecycleCommandService accountLifecycleCommandService;

    public AccountController(AccountService accountService,
                             AccountGroupService accountGroupService,
                             AccountOnlineCommandService accountOnlineCommandService,
                             AccountLifecycleCommandService accountLifecycleCommandService) {
        this.accountService = accountService;
        this.accountGroupService = accountGroupService;
        this.accountOnlineCommandService = accountOnlineCommandService;
        this.accountLifecycleCommandService = accountLifecycleCommandService;
    }

    /**
     * A1 账号分页列表(SQL 下推筛选)。
     *
     * @param query 查询参数(分页 + 可选筛选字段)
     * @return 分页账号列表
     */
    @GetMapping
    public ApiResponse<PageResult<AccountListVO>> list(@ModelAttribute AccountQuery query) {
        return ApiResponse.ok(accountService.listAccounts(query));
    }

    /**
     * A2 账号统计卡(平台级聚合)。
     *
     * @return 统计卡数据(total/online/offline/banned/risk/assigned/unassigned)
     */
    @GetMapping("/stats")
    public ApiResponse<AccountStatsVO> stats() {
        return ApiResponse.ok(accountService.getStats());
    }

    /**
     * A3 发起单账号上线(后端自动分配空闲代理)。
     *
     * <p>返回的 {@code accepted=true} 只代表上线命令已进入本地 outbox,不代表账号已经 ONLINE;
     * 真正登录状态由后续 Kafka 回写切片更新。</p>
     *
     * @param id 账号 ID
     * @return outbox 上线命令受理回执
     */
    @PostMapping("/{id}/online")
    public ApiResponse<AccountOnlineVO> online(@PathVariable("id") Long id) {
        return ApiResponse.ok(accountOnlineCommandService.online(id));
    }

    /**
     * A4 批量发起账号上线(后端逐账号分配空闲代理,批量写入 outbox)。
     *
     * <p>一次最多 500 个账号。返回的 accepted 表示已写入 outbox 的命令数,
     * 不代表账号最终在线状态;最终登录状态由后续 Kafka 回写切片更新。</p>
     *
     * @param request 账号 ID 列表
     * @return outbox 批量上线命令受理汇总
     */
    @PostMapping("/batch-online")
    public ApiResponse<AccountBatchOnlineVO> batchOnline(@RequestBody AccountIdsDTO request) {
        return ApiResponse.ok(accountOnlineCommandService.onlineBatch(request.ids()));
    }

    /**
     * A4.1 主动从协议层拉一次账号状态快照。
     *
     * <p>只用于账号页人工刷新/诊断;本接口不落账号登录态,本地状态仍由 Kafka 事件回填。</p>
     *
     * @param id 账号 ID
     * @return 协议层状态快照
     */
    @PostMapping("/{id}/refresh-status")
    public ApiResponse<AccountStatusVO> refreshStatus(@PathVariable("id") Long id) {
        return ApiResponse.ok(accountLifecycleCommandService.refreshStatus(id));
    }

    /**
     * A4.2 主动探活账号。
     *
     * <p>probe 会真实触达 WhatsApp,仅用于人工诊断或关键操作前确认。</p>
     *
     * @param id 账号 ID
     * @return 探活结果
     */
    @PostMapping("/{id}/probe")
    public ApiResponse<AccountProbeVO> probe(@PathVariable("id") Long id) {
        return ApiResponse.ok(accountLifecycleCommandService.probe(id));
    }

    /**
     * A5 批量发起账号下线(批量写入 outbox)。
     *
     * <p>一次最多 500 个账号。返回的 accepted 表示已写入 outbox 的命令数,
     * 不代表账号最终离线状态;最终登录状态由后续 Kafka 回写切片更新。</p>
     *
     * @param request 账号 ID 列表
     * @return outbox 批量下线命令受理汇总
     */
    @PostMapping("/batch-offline")
    public ApiResponse<AccountBatchOnlineVO> batchOffline(@RequestBody AccountIdsDTO request) {
        return ApiResponse.ok(accountOnlineCommandService.offlineBatch(request.ids()));
    }

    /**
     * A6 批量迁移分组。
     *
     * <p>若 accountGroupId 为 null 且 newGroupName 非空,先新建分组再迁移;
     * 否则直接用 accountGroupId 迁移。</p>
     *
     * @param dto 迁移请求(ids + 目标分组 ID 或新分组名)
     * @return 空成功响应
     */
    @PostMapping("/batch-migrate-group")
    public ApiResponse<Void> batchMigrateGroup(@RequestBody AccountMigrateGroupDTO dto) {
        Long resolvedGroupId;
        if (dto.accountGroupId() == null
                && dto.newGroupName() != null
                && !dto.newGroupName().isBlank()) {
            // 先新建分组,取回 id
            resolvedGroupId = accountGroupService
                    .create(new AccountGroupDTO(dto.newGroupName(), dto.newGroupRemark()))
                    .id();
        } else {
            resolvedGroupId = dto.accountGroupId();
        }
        accountService.migrateGroup(dto.ids(), resolvedGroupId);
        return ApiResponse.ok();
    }

    /**
     * A7 批量软删除账号(全或无严格口径)。
     *
     * <p>仅封禁/导出/解绑状态且不在任务中的账号可删除;任一不满足整批拒删抛 BusinessException。</p>
     *
     * @param request 账号 ID 列表
     * @return 空成功响应
     */
    @PostMapping("/batch-delete")
    public ApiResponse<Void> batchDelete(@RequestBody AccountIdsDTO request) {
        accountService.batchDelete(request.ids());
        return ApiResponse.ok();
    }
}
