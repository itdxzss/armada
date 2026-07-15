package com.armada.account.controller;

import com.armada.account.model.dto.AccountBatchPreviewDTO;
import com.armada.account.model.dto.AccountBatchQueryDTO;
import com.armada.account.model.dto.AccountGroupDTO;
import com.armada.account.model.dto.AccountIdsDTO;
import com.armada.account.model.dto.AccountLifecycleBatchDTO;
import com.armada.account.model.dto.AccountMigrateGroupDTO;
import com.armada.account.model.dto.AccountQuery;
import com.armada.account.model.dto.AccountWsPhoneExportDTO;
import com.armada.account.model.vo.AccountBatchCommandResultVO;
import com.armada.account.model.vo.AccountBatchOnlineVO;
import com.armada.account.model.vo.AccountBatchPreviewVO;
import com.armada.account.model.vo.AccountListVO;
import com.armada.account.model.vo.AccountOnlineAttemptLogVO;
import com.armada.account.model.vo.AccountOnlineVO;
import com.armada.account.model.vo.AccountProbeVO;
import com.armada.account.model.vo.AccountStatsVO;
import com.armada.account.model.vo.AccountStatusVO;
import com.armada.account.model.vo.AccountWsPhoneExportFile;
import com.armada.account.service.AccountBatchLifecycleService;
import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountLifecycleCommandService;
import com.armada.account.service.AccountOnlineAttemptLogService;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.account.service.AccountService;
import com.armada.account.service.AccountWsPhoneExportService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final AccountBatchLifecycleService accountBatchLifecycleService;
    private final AccountLifecycleCommandService accountLifecycleCommandService;
    private final AccountOnlineAttemptLogService accountOnlineAttemptLogService;
    private final AccountWsPhoneExportService accountWsPhoneExportService;

    public AccountController(AccountService accountService,
                             AccountGroupService accountGroupService,
                             AccountOnlineCommandService accountOnlineCommandService,
                             AccountBatchLifecycleService accountBatchLifecycleService,
                             AccountLifecycleCommandService accountLifecycleCommandService,
                             AccountOnlineAttemptLogService accountOnlineAttemptLogService,
                             AccountWsPhoneExportService accountWsPhoneExportService) {
        this.accountService = accountService;
        this.accountGroupService = accountGroupService;
        this.accountOnlineCommandService = accountOnlineCommandService;
        this.accountBatchLifecycleService = accountBatchLifecycleService;
        this.accountLifecycleCommandService = accountLifecycleCommandService;
        this.accountOnlineAttemptLogService = accountOnlineAttemptLogService;
        this.accountWsPhoneExportService = accountWsPhoneExportService;
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
     * 导出前端勾选且账号状态正常的 WS 号码。
     *
     * <p>成功直接返回 UTF-8 TXT 文件；业务失败由全局异常处理器返回统一 JSON，避免生成空文件。</p>
     *
     * @param request 所选账号 ID 和可选分组名称
     * @return TXT 附件响应，X-Export-Count 为实际写入号码数
     */
    @PostMapping("/export-ws-phones")
    public ResponseEntity<byte[]> exportWsPhones(@RequestBody AccountWsPhoneExportDTO request) {
        // 步骤一：交给领域服务完成筛选、清洗、去重和文件内容组装。
        AccountWsPhoneExportFile file = accountWsPhoneExportService.export(request);

        // 步骤二：按 UTF-8 TXT 附件协议写入文件名、长度和实际导出数量。
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/plain;charset=UTF-8"));
        headers.setContentDisposition(org.springframework.http.ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build());
        headers.setContentLength(file.bytes().length);
        headers.set("X-Export-Count", String.valueOf(file.exportedCount()));

        // 步骤三：显式暴露附件名和统计头，便于跨域前端下载并展示成功数量。
        headers.setAccessControlExposeHeaders(List.of(
                HttpHeaders.CONTENT_DISPOSITION,
                "X-Export-Count"));
        return ResponseEntity.ok().headers(headers).body(file.bytes());
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
     * <p>ids 请求一次最多 2000 个账号，由后端按 500 个拆分并跳过不可登录账号。返回的 accepted
     * 表示已写入 outbox 的命令数，不代表账号最终在线状态；最终登录状态由后续 Kafka 回写更新。
     * 携带 accounts/protocolBackend 的既有调用继续走原命令服务。</p>
     *
     * @param request 账号列表;新请求体可为每个账号携带协议后端
     * @return outbox 批量上线命令受理汇总
     */
    @PostMapping("/batch-online")
    public ApiResponse<AccountBatchCommandResultVO> batchOnline(@RequestBody AccountLifecycleBatchDTO request) {
        if (request.hasAccounts()) {
            return ApiResponse.ok(AccountBatchCommandResultVO.from(
                    accountOnlineCommandService.onlineBatchWithProtocolBackends(request.commandItems())));
        }
        return ApiResponse.ok(accountBatchLifecycleService.onlineByIds(request.ids()));
    }

    /**
     * A4.0 批量发起一键抢登。
     *
     * <p>只允许全部为“被抢登”的账号进入抢登中并写入上线 outbox。最终在线状态仍由 Kafka 回写。</p>
     *
     * @param request 账号 ID 列表
     * @return outbox 批量上线命令受理汇总
     */
    @PostMapping("/batch-takeover")
    public ApiResponse<AccountBatchOnlineVO> batchTakeover(@RequestBody AccountIdsDTO request) {
        return ApiResponse.ok(accountOnlineCommandService.takeoverBatch(request.ids()));
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
     * A4.3 查询账号最近上线尝试诊断记录。
     *
     * @param id 账号 ID
     * @param limit 返回数量上限
     * @return 最近诊断记录,最近优先
     */
    @GetMapping("/{id:\\d+}/online-attempts")
    public ApiResponse<List<AccountOnlineAttemptLogVO>> onlineAttempts(
            @PathVariable("id") Long id,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ApiResponse.ok(accountOnlineAttemptLogService.recentByAccount(id, limit));
    }

    /**
     * A4.4 查询单次上线尝试诊断时间线。
     *
     * @param onlineAttemptId 上线尝试 ID
     * @param limit 返回数量上限
     * @return 诊断时间线,时间升序
     */
    @GetMapping("/online-attempts/{onlineAttemptId}")
    public ApiResponse<List<AccountOnlineAttemptLogVO>> onlineAttemptTimeline(
            @PathVariable("onlineAttemptId") String onlineAttemptId,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return ApiResponse.ok(accountOnlineAttemptLogService.timeline(onlineAttemptId, limit));
    }

    /**
     * A5 批量发起账号下线(批量写入 outbox)。
     *
     * <p>ids 请求一次最多 2000 个账号，由后端按 1000 个拆分。返回的 accepted 表示已写入
     * outbox 的命令数，不代表账号最终离线状态；最终登录状态由后续 Kafka 回写更新。
     * 携带 accounts/protocolBackend 的既有调用继续走原命令服务。</p>
     *
     * @param request 账号列表;新请求体可为每个账号携带协议后端
     * @return outbox 批量下线命令受理汇总
     */
    @PostMapping("/batch-offline")
    public ApiResponse<AccountBatchCommandResultVO> batchOffline(@RequestBody AccountLifecycleBatchDTO request) {
        if (request.hasAccounts()) {
            return ApiResponse.ok(AccountBatchCommandResultVO.from(
                    accountOnlineCommandService.offlineBatchWithProtocolBackends(request.commandItems())));
        }
        return ApiResponse.ok(accountBatchLifecycleService.offlineByIds(request.ids()));
    }

    /**
     * 按账号列表已生效筛选条件发起批量登录。
     *
     * <p>请求体不包含分页；空对象表示当前租户全部未软删账号。目标查询、跳过和分片均在后端完成。</p>
     *
     * @param query 已生效筛选条件
     * @return 全部内部批次的聚合结果
     */
    @PostMapping("/batch-online-by-query")
    public ApiResponse<AccountBatchCommandResultVO> batchOnlineByQuery(
            @RequestBody AccountBatchQueryDTO query) {
        return ApiResponse.ok(accountBatchLifecycleService.onlineByQuery(query));
    }

    /**
     * 按账号列表已生效筛选条件发起批量离线。
     *
     * <p>请求体不包含分页；空对象表示当前租户全部未软删账号。目标查询和分片均在后端完成。</p>
     *
     * @param query 已生效筛选条件
     * @return 全部内部批次的聚合结果
     */
    @PostMapping("/batch-offline-by-query")
    public ApiResponse<AccountBatchCommandResultVO> batchOfflineByQuery(
            @RequestBody AccountBatchQueryDTO query) {
        return ApiResponse.ok(accountBatchLifecycleService.offlineByQuery(query));
    }

    /**
     * 预估批量登录或离线的操作范围。
     *
     * <p>范围必须显式指定 IDS 或 QUERY。返回值是确认前快照，最终数量以执行接口汇总为准。</p>
     *
     * @param request 操作类型和显式范围
     * @return 匹配、预计执行及跳过数量
     */
    @PostMapping("/batch-operation-preview")
    public ApiResponse<AccountBatchPreviewVO> previewBatchOperation(
            @RequestBody AccountBatchPreviewDTO request) {
        return ApiResponse.ok(accountBatchLifecycleService.preview(request));
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
