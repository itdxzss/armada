package com.armada.promotion.stats;

import static com.armada.promotion.stats.BuyerChannelStatsModels.*;

import com.armada.shared.response.ApiResponse;
import com.armada.shared.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 渠道统计查询、日数据维护和导出接口。 */
@RestController
@RequestMapping("/api/buyer/channel-stats")
@PreAuthorize("hasAuthority('tenant:buyer-channel-stats:view')")
public class BuyerChannelStatsController {

    private static final Logger log = LoggerFactory.getLogger(BuyerChannelStatsController.class);

    private final BuyerChannelStatsService service;

    public BuyerChannelStatsController(BuyerChannelStatsService service) {
        this.service = service;
    }

    /** 查询页面筛选项。 */
    @GetMapping("/options")
    public ApiResponse<Options> options(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.options(principal.tenantId()));
    }

    /** 查询渠道区间汇总。 */
    @GetMapping
    public ApiResponse<List<StatsRow>> list(
            @ModelAttribute Query query,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.list(query, principal.tenantId()));
    }

    /** 查询单渠道每日统计。 */
    @GetMapping("/{channelId}/daily")
    public ApiResponse<List<DailyRow>> daily(
            @PathVariable long channelId,
            @RequestParam String countryCode,
            @RequestParam String dateStart,
            @RequestParam String dateEnd,
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.ok(service.daily(channelId, countryCode, dateStart, dateEnd, principal.tenantId()));
    }

    /** 保存人工录入的当日广告数据，采用版本号防止并发覆盖。 */
    @PutMapping("/{channelId}/daily/{date}")
    @PreAuthorize("hasAuthority('tenant:buyer-channel-stats:edit')")
    public ApiResponse<UpdateResult> update(
            @PathVariable long channelId,
            @PathVariable String date,
            @RequestBody DailyInput input,
            @AuthenticationPrincipal AuthPrincipal principal) {
        try {
            return ApiResponse.ok(service.update(channelId, LocalDate.parse(date), input, principal));
        } catch (DateTimeParseException ex) {
            throw new com.armada.shared.exception.BusinessException(
                    com.armada.shared.exception.ErrorCode.VALIDATION, "统计日期格式应为yyyy-MM-dd");
        }
    }

    /** 导出当前筛选结果为 Excel 可直接打开的 UTF-8 CSV。 */
    @GetMapping("/export")
    @PreAuthorize("hasAuthority('tenant:buyer-channel-stats:export')")
    public void export(
            @ModelAttribute Query query,
            @AuthenticationPrincipal AuthPrincipal principal,
            HttpServletResponse response) throws IOException {
        List<StatsRow> rows = service.list(query, principal.tenantId());
        String filename = "渠道统计_" + query.dateStart() + "_" + query.dateEnd() + ".csv";
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" +
                URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
        StringBuilder csv = new StringBuilder("\uFEFF渠道名称,渠道码,国家,模板,消耗,展示,点击,总费用,请求登录人数,登录成功人数,解绑数\r\n");
        for (StatsRow row : rows) {
            csv.append(cell(row.channelName())).append(',').append(cell(row.channelCode())).append(',')
                    .append(cell(row.countryName())).append(',').append(cell(row.templateName())).append(',')
                    .append(row.spend()).append(',').append(row.impressions()).append(',').append(row.clicks()).append(',')
                    .append(row.totalFee()).append(',').append(row.loginRequestUserCount()).append(',')
                    .append(row.loginSuccessUserCount()).append(',').append(row.unbindCount()).append("\r\n");
        }
        response.getWriter().write(csv.toString());
        log.info("渠道统计导出 tenantId={} rowCount={} operator={}",
                principal.tenantId(), rows.size(), principal.username());
    }

    private static String cell(String value) {
        String safe = value == null ? "" : value.replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }
}
