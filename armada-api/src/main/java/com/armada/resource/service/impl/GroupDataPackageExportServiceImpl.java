package com.armada.resource.service.impl;

import com.armada.resource.mapper.GroupDataPackageMapper;
import com.armada.resource.mapper.GroupDataPackagePhoneMapper;
import com.armada.resource.mapper.GroupDataPackageStatMapper;
import com.armada.resource.model.entity.GroupDataPackage;
import com.armada.resource.model.entity.GroupDataPackagePhone;
import com.armada.resource.model.entity.GroupDataPackageStat;
import com.armada.resource.model.enums.GroupDataPackagePhoneStatus;
import com.armada.resource.model.vo.GroupDataPackageExportVO;
import com.armada.resource.service.GroupDataPackageExportService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 导出单次至多50万号码；包锁冻结当前代，避免计数和内容期间被覆盖。 */
@Service
public class GroupDataPackageExportServiceImpl implements GroupDataPackageExportService {
    private static final int MAX_PACKAGES = 100;
    private static final long MAX_EXPORT_PHONES = 500_000;
    private final GroupDataPackageMapper packages;
    private final GroupDataPackagePhoneMapper phones;
    private final GroupDataPackageStatMapper stats;
    /** 注入导出所需数据访问。 */
    public GroupDataPackageExportServiceImpl(GroupDataPackageMapper packages, GroupDataPackagePhoneMapper phones,
            GroupDataPackageStatMapper stats) {
        this.packages = packages; this.phones = phones; this.stats = stats;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GroupDataPackageExportVO export(List<Long> ids, String usageStatus, String format) {
        validate(ids, format);
        List<Integer> statuses = statuses(usageStatus);
        for (long id : ids) { require(id, false); }
        // 所有批量资源写入统一按ID顺序拿包锁。
        List<GroupDataPackage> parents = ids.stream().sorted().map(id -> require(id, true)).toList();
        long expected = parents.stream().mapToLong(parent -> count(stats.select(parent.getId()), usageStatus)).sum();
        if (expected > MAX_EXPORT_PHONES) { throw invalid("单次导出最多500000个号码，请缩小范围"); }
        boolean csv = "csv".equals(format);
        StringBuilder body = new StringBuilder(csv ? "\uFEFF数据包,号码,管理员,国家,状态\n" : "");
        long exported = 0;
        for (GroupDataPackage parent : parents) {
            List<GroupDataPackagePhone> rows = phones.export(parent.getId(), parent.getGeneration(), statuses);
            for (GroupDataPackagePhone row : rows) { append(body, parent.getName(), row, csv); }
            exported += rows.size();
        }
        String base = parents.size() == 1 ? parents.get(0).getName() : "拉群数据包_" + parents.size() + "包";
        String filename = base.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_") + "_" + usageStatus + "." + format;
        return new GroupDataPackageExportVO(filename, csv ? "text/csv;charset=UTF-8" : "text/plain;charset=UTF-8",
                body.toString().getBytes(StandardCharsets.UTF_8), exported);
    }

    private GroupDataPackage require(long id, boolean lock) {
        GroupDataPackage row = lock ? packages.lockActive(id) : packages.selectActive(id);
        if (row == null) { throw new BusinessException(ErrorCode.NOT_FOUND, "数据包不存在或已删除"); }
        return row;
    }

    private static void append(StringBuilder output, String name, GroupDataPackagePhone row, boolean csv) {
        String material = row.getPhone() + (Boolean.TRUE.equals(row.getAdminRequired()) ? "A" : "");
        if (csv) {
            output.append(cell(name)).append(',').append(cell(row.getPhone())).append(',')
                    .append(Boolean.TRUE.equals(row.getAdminRequired()) ? "A" : "").append(',')
                    .append(cell(row.getCountryIso2())).append(',')
                    .append(GroupDataPackagePhoneStatus.of(row.getStatus()).name()).append('\n');
        } else { output.append(material).append('\n'); }
    }
    private static String cell(String value) {
        String safe = value == null ? "" : value;
        if (safe.matches("^[=+@-].*")) { safe = "'" + safe; }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
    private static List<Integer> statuses(String usage) {
        return switch (usage) {
            case "all" -> List.of();
            case "unused" -> List.of(GroupDataPackagePhoneStatus.UNUSED.code());
            case "success" -> List.of(GroupDataPackagePhoneStatus.SUCCESS.code());
            case "failed" -> List.of(GroupDataPackagePhoneStatus.RETRYABLE_FAILED.code(),
                    GroupDataPackagePhoneStatus.PRIVACY_REJECTED.code(), GroupDataPackagePhoneStatus.UNREGISTERED.code());
            case "privacy_rejected" -> List.of(GroupDataPackagePhoneStatus.PRIVACY_REJECTED.code());
            default -> throw invalid("导出状态不合法");
        };
    }
    private static long count(GroupDataPackageStat stats, String usage) {
        return switch (usage) {
            case "all" -> stats.getTotalCount(); case "unused" -> stats.getUnusedCount();
            case "success" -> stats.getSuccessCount(); case "failed" -> stats.getFailedCount();
            case "privacy_rejected" -> stats.getPrivacyRejectedCount();
            default -> throw invalid("导出状态不合法");
        };
    }
    private static void validate(List<Long> ids, String format) {
        if (ids == null || ids.isEmpty() || ids.size() > MAX_PACKAGES
                || ids.stream().anyMatch(id -> id == null || id < 1)
                || new HashSet<>(ids).size() != ids.size()) { throw invalid("请选择1至100个不重复的数据包"); }
        if (!"txt".equals(format) && !"csv".equals(format)) { throw invalid("导出格式不合法"); }
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION, message); }
}
