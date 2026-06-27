package com.armada.group.controller;

import com.armada.group.model.dto.GroupLinkImportDetailQuery;
import com.armada.group.model.vo.GroupLinkImportDetailVO;
import com.armada.group.service.GroupLinkImportService;
import com.armada.shared.response.ApiResponse;
import com.armada.shared.response.PageResult;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 群链接导入端点(C1 明细分页列表、C2 导出失败)。
 *
 * <p>Controller 只做参数接收与响应组装,业务规则全部在 Service。</p>
 */
@RestController
@RequestMapping("/api/group-link-imports")
public class GroupLinkImportController {

    private final GroupLinkImportService service;

    public GroupLinkImportController(GroupLinkImportService service) {
        this.service = service;
    }

    /**
     * C1 导入明细分页列表。
     *
     * @param query 查询条件(labelId/batchId/result/failReason/page/pageSize;result=1成功、2失败)
     * @return 分页明细 VO
     */
    @GetMapping("/details")
    public ApiResponse<PageResult<GroupLinkImportDetailVO>> details(
            @ModelAttribute GroupLinkImportDetailQuery query) {
        return ApiResponse.ok(service.listDetails(query));
    }

    /**
     * C2 导出失败明细(result=失败)为 CSV 文件。
     *
     * <p>响应:UTF-8 BOM(防 Excel 中文乱码)+ 表头 + 数据行;Content-Disposition=attachment。</p>
     *
     * @param labelId 分组 ID(可选)
     * @param batchId 批次 ID(可选)
     * @param resp    HTTP 响应(直接写流)
     * @throws IOException IO 异常
     */
    @GetMapping("/failed/export")
    public void exportFailed(
            @RequestParam(value = "label_id", required = false) Long labelId,
            @RequestParam(value = "batch_id", required = false) Long batchId,
            HttpServletResponse resp) throws IOException {
        resp.setContentType("text/csv;charset=UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=failed.csv");

        List<String[]> rows = service.exportFailed(labelId, batchId);

        try (PrintWriter writer = resp.getWriter()) {
            // UTF-8 BOM,防 Excel 打开中文 CSV 乱码
            writer.write("﻿");
            // 表头
            writer.println(escapeCsvRow(new String[]{"行号", "群名称", "群链接", "失败原因", "导入时间"}));
            // 数据行
            for (String[] row : rows) {
                writer.println(escapeCsvRow(row));
            }
            writer.flush();
        }
    }

    /**
     * 将一行字段数组转义为 CSV 行字符串。
     * 含逗号/双引号/换行的字段加双引号包裹,字段内双引号用两个双引号转义。
     *
     * @param fields 字段数组
     * @return 一行 CSV 字符串(不含末尾换行)
     */
    static String escapeCsvRow(String[] fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String field = fields[i] == null ? "" : fields[i];
            if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
                sb.append('"').append(field.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(field);
            }
        }
        return sb.toString();
    }
}
