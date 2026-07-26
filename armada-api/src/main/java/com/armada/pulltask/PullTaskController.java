package com.armada.pulltask;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * “拉群任务”页面接口。
 *
 * <p>该业务与已有进群任务、拉群营销相互独立。本类只保存页面配置并提供查询；
 * 在协议执行器尚未接入前，启动类操作会返回明确业务提示，避免把任务误报为已执行。</p>
 */
@RestController
@RequestMapping("/api/pull-tasks")
@PreAuthorize("hasAuthority('tenant:pull_task:view')")
public class PullTaskController {

    private static final Logger log = LoggerFactory.getLogger(PullTaskController.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PullTaskController(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 分页查询当前租户真实保存的拉群任务。 */
    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @ModelAttribute PullTaskQuery query,
            @AuthenticationPrincipal AuthPrincipal principal) {
        int page = Math.max(1, query.page == null ? 1 : query.page);
        int pageSize = Math.min(200, Math.max(1, query.pageSize == null ? 10 : query.pageSize));
        StringBuilder where = new StringBuilder(" WHERE tenant_id=? AND deleted_at IS NULL");
        List<Object> args = new ArrayList<>();
        args.add(principal.tenantId());
        append(where, args, query.id != null, " AND id=?", query.id);
        append(where, args, hasText(query.status), " AND status=?", query.status);
        append(where, args, hasText(query.mode), " AND mode=?", query.mode);
        if (hasText(query.keyword)) {
            where.append(" AND (task_name LIKE ? OR group_name LIKE ?)");
            String keyword = "%" + query.keyword.trim() + "%";
            args.add(keyword);
            args.add(keyword);
        }
        if (hasText(query.operator)) {
            where.append(" AND operator_name LIKE ?");
            args.add("%" + query.operator.trim() + "%");
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM pull_task" + where,
                Long.class, args.toArray());
        if (total == 0) {
            return ApiResponse.ok(PageResult.of(List.of(), page, pageSize, 0));
        }
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((page - 1) * pageSize);
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id,task_name,group_name,mode,status,group_count,expected_pull_count," +
                        "operator_name,created_at,updated_at,remark FROM pull_task" + where +
                        " ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, n) -> row(rs.getLong("id"), rs.getString("task_name"),
                        rs.getString("group_name"), rs.getString("mode"), rs.getString("status"),
                        rs.getInt("group_count"), rs.getInt("expected_pull_count"),
                        rs.getString("operator_name"), rs.getLong("created_at"),
                        rs.getLong("updated_at"), rs.getString("remark")),
                pageArgs.toArray());
        log.info("拉群任务列表查询 tenantId={} page={} pageSize={} total={}",
                principal.tenantId(), page, pageSize, total);
        return ApiResponse.ok(PageResult.of(rows, page, pageSize, total));
    }

    /** 保存页面配置快照，不触碰其他任务业务表。 */
    @PostMapping
    @PreAuthorize("hasAuthority('tenant:pull_task:create')")
    @Transactional(rollbackFor = Exception.class)
    public ApiResponse<Map<String, Object>> create(
            @RequestBody Map<String, Object> request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        String taskName = text(request.get("taskName"));
        if (taskName.isBlank() || taskName.length() > 128) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务名称不能为空且不能超过128个字符");
        }
        String mode = text(request.get("subMode"));
        if (!"OLD_LINK".equals(mode) && !"CREATE_NEW".equals(mode)) {
            throw new BusinessException(ErrorCode.VALIDATION, "拉群任务模式无效");
        }
        int groupCount = listSize(request.get("groupLinkIds")) + listSize(request.get("pastedLinks"));
        if ("OLD_LINK".equals(mode) && groupCount == 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "老群链接任务至少需要一个群链接");
        }
        int expectedPullCount = nonBlankLineCount(text(request.get("materialText")));
        String groupName = mapText(request.get("groupProfile"), "groupName");
        String remark = nullableText(request.get("remark"));
        long now = System.currentTimeMillis();
        String config;
        try {
            config = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "任务配置格式错误");
        }
        jdbc.update("INSERT INTO pull_task (tenant_id,task_name,mode,status,group_name,group_count," +
                        "expected_pull_count,config_json,operator_name,created_by,remark,created_at,updated_at) " +
                        "VALUES (?,?,?,'WAIT_START',?,?,?,?,?,?,?,?,?)",
                principal.tenantId(), taskName, mode, nullable(groupName), groupCount,
                expectedPullCount, config, displayName(principal), principal.userId(), remark, now, now);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        log.info("拉群任务配置已保存 tenantId={} taskId={} mode={} groupCount={} expectedPullCount={} operator={}",
                principal.tenantId(), id, mode, groupCount, expectedPullCount, principal.username());
        return ApiResponse.ok(requireRow(principal.tenantId(), id));
    }

    /** 查询任务配置详情。 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        Map<String, Object> result = requireRow(principal.tenantId(), id);
        String json = jdbc.queryForObject(
                "SELECT CAST(config_json AS CHAR) FROM pull_task WHERE tenant_id=? AND id=? AND deleted_at IS NULL",
                String.class, principal.tenantId(), id);
        try {
            result.put("config", objectMapper.readValue(json, MAP_TYPE));
        } catch (JsonProcessingException ex) {
            log.error("拉群任务配置解析失败 tenantId={} taskId={}", principal.tenantId(), id, ex);
            throw new BusinessException(ErrorCode.CONFLICT, "任务配置已损坏，请联系管理员");
        }
        result.put("summary", summary(result));
        return ApiResponse.ok(result);
    }

    /** 尚未产生执行明细时返回真实空页，而不是前端模拟数据。 */
    @GetMapping("/{id}/groups")
    public ApiResponse<PageResult<Map<String, Object>>> groups(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireRow(principal.tenantId(), id);
        int safePage = Math.max(1, page);
        int safePageSize = Math.min(200, Math.max(1, pageSize));
        return ApiResponse.ok(PageResult.of(List.of(), safePage, safePageSize, 0));
    }

    @PostMapping({"/{id}/start", "/{id}/pause", "/{id}/stop"})
    @PreAuthorize("hasAuthority('tenant:pull_task:operate')")
    public ApiResponse<Map<String, Object>> lifecycle(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireRow(principal.tenantId(), id);
        log.warn("拉群任务执行操作被拒绝：执行器未接入 tenantId={} taskId={} operator={}",
                principal.tenantId(), id, principal.username());
        throw new BusinessException(ErrorCode.VALIDATION, "拉群任务执行器尚未接入，当前只能保存和查看任务配置");
    }

    @PostMapping("/batch-delete")
    @PreAuthorize("hasAuthority('tenant:pull_task:delete')")
    @Transactional(rollbackFor = Exception.class)
    public ApiResponse<Integer> batchDelete(
            @RequestBody Map<String, Object> request,
            @AuthenticationPrincipal AuthPrincipal principal) {
        List<Long> ids = longList(request.get("ids"));
        if (ids.isEmpty()) {
            return ApiResponse.ok(0);
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        long now = System.currentTimeMillis();
        args.add(now);
        args.add(now);
        args.add(principal.tenantId());
        args.addAll(ids);
        int affected = jdbc.update("UPDATE pull_task SET deleted_at=?,updated_at=? " +
                "WHERE tenant_id=? AND deleted_at IS NULL AND status IN ('WAIT_START','COMPLETED','ENDED') " +
                "AND id IN (" + placeholders + ")", args.toArray());
        log.info("拉群任务批量删除 tenantId={} requested={} affected={} operator={}",
                principal.tenantId(), ids.size(), affected, principal.username());
        return ApiResponse.ok(affected);
    }

    @PostMapping({"/{id}/groups/supplement-pullers", "/{id}/groups/operations",
            "/{id}/groups/task-operations"})
    @PreAuthorize("hasAuthority('tenant:pull_task:operate')")
    public ApiResponse<Integer> unsupportedGroupOperation(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireRow(principal.tenantId(), id);
        throw new BusinessException(ErrorCode.VALIDATION, "任务尚未开始执行，没有可操作的群组明细");
    }

    @GetMapping({"/{id}/export-report", "/{id}/export-links", "/{id}/export-resources"})
    @PreAuthorize("hasAuthority('tenant:pull_task:export')")
    public ApiResponse<Map<String, String>> export(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal) {
        Map<String, Object> task = requireRow(principal.tenantId(), id);
        String content = "任务ID\t任务名称\t模式\t状态\t群数量\t预计拉人数\n" +
                task.get("id") + "\t" + task.get("taskName") + "\t" + task.get("mode") + "\t" +
                task.get("status") + "\t" + task.get("groupCount") + "\t" + task.get("expectedPullCount") + "\n";
        return ApiResponse.ok(Map.of("filename", "拉群任务_" + id + ".txt", "content", content));
    }

    private Map<String, Object> requireRow(long tenantId, Long id) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT id,task_name,group_name,mode,status,group_count,expected_pull_count," +
                        "operator_name,created_at,updated_at,remark FROM pull_task " +
                        "WHERE tenant_id=? AND id=? AND deleted_at IS NULL",
                (rs, n) -> row(rs.getLong("id"), rs.getString("task_name"),
                        rs.getString("group_name"), rs.getString("mode"), rs.getString("status"),
                        rs.getInt("group_count"), rs.getInt("expected_pull_count"),
                        rs.getString("operator_name"), rs.getLong("created_at"),
                        rs.getLong("updated_at"), rs.getString("remark")), tenantId, id);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "拉群任务不存在");
        }
        return rows.get(0);
    }

    private static Map<String, Object> row(long id, String taskName, String groupName,
                                            String mode, String status, int groupCount,
                                            int expectedPullCount, String operator,
                                            long createdAt, long updatedAt, String remark) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("taskName", taskName);
        row.put("groupName", groupName);
        row.put("mode", mode);
        row.put("status", status);
        row.put("groupCount", groupCount);
        row.put("totalMembers", 0);
        row.put("expectedPullCount", expectedPullCount);
        row.put("joinedCount", 0);
        row.put("failedCount", 0);
        row.put("bannedCount", 0);
        row.put("unusedCount", expectedPullCount);
        row.put("pullerCount", 0);
        row.put("operator", operator);
        row.put("submitted", false);
        row.put("createdAt", createdAt);
        row.put("updatedAt", updatedAt);
        row.put("remark", remark);
        return row;
    }

    private static Map<String, Object> summary(Map<String, Object> task) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", task.get("status"));
        summary.put("mode", task.get("mode"));
        summary.put("groupCount", task.get("groupCount"));
        summary.put("totalMembers", 0);
        summary.put("abnormalCount", 0);
        summary.put("joinedCount", 0);
        summary.put("unusedCount", task.get("unusedCount"));
        summary.put("expectedPullCount", task.get("expectedPullCount"));
        return summary;
    }

    private static void append(StringBuilder sql, List<Object> args, boolean enabled,
                               String fragment, Object value) {
        if (enabled) {
            sql.append(fragment);
            args.add(value);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String nullableText(Object value) {
        String result = text(value);
        return result.isEmpty() ? null : result;
    }

    private static Object nullable(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static int listSize(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    private static int nonBlankLineCount(String value) {
        return (int) value.lines().filter(line -> !line.isBlank()).count();
    }

    private static String mapText(Object value, String key) {
        return value instanceof Map<?, ?> map ? text(map.get(key)) : "";
    }

    private static String displayName(AuthPrincipal principal) {
        return hasText(principal.nickname()) ? principal.nickname() : principal.username();
    }

    private static List<Long> longList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Long> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Number number) {
                result.add(number.longValue());
            }
        }
        return result.stream().distinct().toList();
    }

    /** 页面筛选参数。 */
    public static final class PullTaskQuery {
        private Integer page;
        private Integer pageSize;
        private Long id;
        private String keyword;
        private String status;
        private String mode;
        private String operator;

        public void setPage(Integer page) { this.page = page; }
        public void setPageSize(Integer pageSize) { this.pageSize = pageSize; }
        public void setId(Long id) { this.id = id; }
        public void setKeyword(String keyword) { this.keyword = keyword; }
        public void setStatus(String status) { this.status = status; }
        public void setMode(String mode) { this.mode = mode; }
        public void setOperator(String operator) { this.operator = operator; }
    }
}
