package com.armada.account.service;

import com.armada.account.model.dto.AccountExportMaterial;
import com.armada.account.model.entity.ImportFormat;
import com.armada.account.model.enums.AccountDeviceOsCode;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Component;

/** 按原始凭据格式分文件，限制体积并使用服务端文件名，避免路径注入。 */
@Component
public class AccountExportArchive {
    /** 限制单个下载及数据库产物体积，避免大凭据耗尽内存。 */
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private final ObjectMapper json = new ObjectMapper();

    /** 全量校验后构造 ZIP；任何缺材料、重复身份或非法格式均整批拒绝。 */
    public byte[] build(List<AccountExportMaterial> materials) {
        if (materials.isEmpty()) throw invalid("没有可导出的账号");
        var ids = new HashSet<Long>();
        Map<String, StringBuilder> files = new LinkedHashMap<>();
        long bytes = 0;
        for (var row : materials) {
            if (row.accountId() == null || !ids.add(row.accountId())) throw invalid("导出账号来源不唯一");
            if (row.rawPayload() == null || row.rawPayload().isBlank()) throw invalid("所选账号缺少原始导出材料");
            bytes += row.rawPayload().getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_BYTES) throw invalid("导出材料超过 4MB，请减少勾选账号后重试");
            var file = files.computeIfAbsent(filename(row), ignored -> new StringBuilder());
            if (!file.isEmpty()) file.append('\n');
            file.append(row.rawPayload());
        }
        try (var bytesOut = new ByteArrayOutputStream();
             var zip = new ZipOutputStream(bytesOut, StandardCharsets.UTF_8)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return bytesOut.toByteArray();
        } catch (IOException ex) {
            throw invalid("账号导出文件生成失败");
        }
    }

    private String filename(AccountExportMaterial row) {
        if (row.importFormat() == null) throw invalid("无法确定账号原始格式");
        var format = ImportFormat.fromCode(row.importFormat());
        if (format == ImportFormat.SIX) {
            int columns = row.rawPayload().split(",", -1).length;
            if (row.rawPayload().contains("\n") || row.rawPayload().contains("\r")
                    || columns < 5 || columns > 6) throw invalid("五/六段原始材料不完整");
            return columns == 5 ? "五段.txt" : "六段.txt";
        }
        try {
            var node = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(row.rawPayload());
            if (node == null || !node.isObject()) throw invalid("JSON 原始材料必须对应单个账号对象");
        } catch (IOException ex) {
            // Jackson 异常可能包含凭据原文，禁止附带 cause 或写日志。
            throw invalid("JSON 原始材料格式错误");
        }
        if (format == ImportFormat.JSON) return "JSON/" + row.accountId() + ".json";
        if (Integer.valueOf(AccountDeviceOsCode.IOS).equals(row.deviceOs())) return "全参-iOS.txt";
        if (Integer.valueOf(AccountDeviceOsCode.ANDROID).equals(row.deviceOs())) return "全参-Android.txt";
        throw invalid("无法确定全参账号的设备系统");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION, message);
    }
}
