package com.armada.resource.model.vo;

import java.util.List;

/**
 * IP 代理批量导入结果。
 *
 * <p>按完整身份 (网关, 端口, 用户名, 密码) 去重：命中已有活跃行→跳过、否则新增（不覆盖既有行）。
 * {@code insertedRows} 为实际写库新行；{@code skippedRows} 为完全重复跳过；{@code failedRows} 为格式不合格。</p>
 *
 * @param totalRows    解析的总行数
 * @param insertedRows 实际新增行数
 * @param skippedRows  重复跳过行数
 * @param failedRows   格式不合格行数
 * @param errors       不合格行的原因（行号 + 说明）
 */
public record IpProxyImportResultVO(
        int totalRows,
        int insertedRows,
        int skippedRows,
        int failedRows,
        List<String> errors) {
}
