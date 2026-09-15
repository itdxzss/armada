package com.armada.resource.service.impl;

import com.armada.resource.model.entity.GroupDataPackageImport;
import com.armada.resource.model.dto.GroupDataPackageQuery;
import com.armada.resource.model.vo.GroupDataPackageImportResultVO;
import com.armada.resource.model.vo.GroupDataPackageImportVO;
import com.armada.resource.service.GroupDataPackageFileParser;
import com.armada.resource.service.GroupDataPackageImportService;
import com.armada.task.service.GroupDataPackageTaskProjectionService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.armada.platform.country.service.CountryService;
import com.armada.platform.country.model.vo.CountryOptionVO;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** 文件读取在事务外，数据库写入委托独立事务Bean。 */
@Service
public class GroupDataPackageImportServiceImpl implements GroupDataPackageImportService {
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private final GroupDataPackageFileParser parser;
    private final GroupDataPackageImportWriter writer;
    private final GroupDataPackageImportAuditService audit;
    private final GroupDataPackageTaskProjectionService projection;
    private final CountryService countries;
    /** 注入解析器、写事务和审计。 */
    public GroupDataPackageImportServiceImpl(GroupDataPackageFileParser parser, GroupDataPackageImportWriter writer,
            GroupDataPackageImportAuditService audit, GroupDataPackageTaskProjectionService projection, CountryService countries) {
        this.parser = parser; this.writer = writer; this.audit = audit; this.projection = projection; this.countries = countries;
    }

    /** {@inheritDoc} */
    @Override
    public GroupDataPackageImportResultVO importPhones(ImportRequest request) {
        validate(request); writer.requireAccessible(request.packageId());
        projection.synchronize(List.of(request.packageId()));
        if ("overwrite".equals(request.mode())) { projection.assertNotActivelyUsed(request.packageId()); }
        GroupDataPackageImport entry = new GroupDataPackageImport();
        entry.setPackageId(request.packageId()); entry.setMode("append".equals(request.mode())
                ? GroupDataPackageImportWriter.APPEND : GroupDataPackageImportWriter.OVERWRITE);
        entry.setFileName(request.file().getOriginalFilename()); entry.setCreatedBy(request.userId());
        entry.setCreatedAt(System.currentTimeMillis()); audit.begin(entry);
        try {
            var parsed = parser.parse(entry.getFileName(), request.file().getBytes());
            Map<String, CountryOptionVO> options = countries.options("marketing-export").rows().stream()
                    .filter(option -> option.iso2() != null)
                    .collect(Collectors.toMap(CountryOptionVO::iso2, Function.identity(), (first, second) -> first));
            return writer.write(new GroupDataPackageImportWriter.ImportJob(entry, parsed, request.privacyFilterDays(),
                    countries.activePhonePrefixResolver(), options));
        } catch (IOException exception) {
            audit.fail(entry, "文件读取失败"); throw invalid("文件读取失败");
        } catch (RuntimeException exception) {
            entry.setAcceptedRows(0);
            audit.fail(entry, exception instanceof BusinessException ? exception.getMessage() : "导入处理失败");
            throw exception;
        }
    }

    /** {@inheritDoc} */
    @Override
    public PageResult<GroupDataPackageImportVO> imports(long id, GroupDataPackageQuery query) {
        writer.requireAccessible(id); return audit.list(id, query);
    }

    private static void validate(ImportRequest request) {
        if (request == null || request.userId() < 1 || request.file() == null || request.file().isEmpty()) {
            throw invalid("请选择TXT文件");
        }
        if (!"append".equals(request.mode()) && !"overwrite".equals(request.mode())) { throw invalid("导入模式不合法"); }
        String name = request.file().getOriginalFilename();
        if (name == null || name.length() > 255 || !name.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            throw invalid("仅支持文件名不超过255字的TXT文件");
        }
        if (request.file().getSize() > MAX_FILE_BYTES) { throw invalid("文件不能超过10MB"); }
        if (request.privacyFilterDays() < 0 || request.privacyFilterDays() > 365) { throw invalid("隐私历史窗口应为0至365天"); }
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION, message); }
}
