package com.armada.hyperlink.data.service.impl;

import com.armada.hyperlink.data.mapper.DataPackageImportMapper;
import com.armada.hyperlink.data.mapper.DataPackageMapper;
import com.armada.hyperlink.data.mapper.DataPackagePhoneMapper;
import com.armada.hyperlink.data.mapper.DataPackageStatMapper;
import com.armada.hyperlink.data.model.entity.DataPackage;
import com.armada.hyperlink.data.model.entity.DataPackageImport;
import com.armada.hyperlink.data.model.entity.DataPackagePhone;
import com.armada.hyperlink.data.model.enums.DataPackageImportMode;
import com.armada.hyperlink.data.model.enums.DataPackageImportStatus;
import com.armada.hyperlink.data.model.enums.DataPackagePoolStatus;
import com.armada.hyperlink.data.model.vo.DataPackageImportResultVO;
import com.armada.hyperlink.data.service.DataPackageImportService;
import com.armada.hyperlink.data.service.DataPackageTxtParser;
import com.armada.hyperlink.data.service.ParsedDataPackagePhones;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.platform.country.service.CountryService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/** TXT 导入审计、包行锁、APPEND 与 generation 覆盖切换实现。 */
@Service
public class DataPackageImportServiceImpl implements DataPackageImportService {

    private static final Logger log = LoggerFactory.getLogger(DataPackageImportServiceImpl.class);
    private static final int FILE_NAME_MAX_LENGTH = 255;
    private static final int QUERY_CHUNK_SIZE = 1_000;
    private static final int INSERT_CHUNK_SIZE = 500;
    private static final int FAILURE_REASON_MAX_LENGTH = 512;
    private static final Map<String, String> FORBIDDEN_IMPORT_COUNTRIES = Map.of(
            "MY", "马来西亚",
            "SG", "新加坡",
            "CN", "中国",
            "HK", "香港",
            "MO", "澳门",
            "TW", "台湾");

    private final DataPackageMapper packageMapper;
    private final DataPackagePhoneMapper phoneMapper;
    private final DataPackageStatMapper statMapper;
    private final DataPackageImportMapper importMapper;
    private final DataPackageTxtParser parser;
    private final CountryService countryService;
    private final TransactionTemplate businessTransaction;
    private final TransactionTemplate auditTransaction;
    private final int maxPhonesPerPackage;

    public DataPackageImportServiceImpl(
            DataPackageMapper packageMapper,
            DataPackagePhoneMapper phoneMapper,
            DataPackageStatMapper statMapper,
            DataPackageImportMapper importMapper,
            DataPackageTxtParser parser,
            CountryService countryService,
            PlatformTransactionManager transactionManager,
            @Value("${armada.hyperlink.data-package.max-phones:500000}") int maxPhonesPerPackage) {
        this.packageMapper = packageMapper;
        this.phoneMapper = phoneMapper;
        this.statMapper = statMapper;
        this.importMapper = importMapper;
        this.parser = parser;
        this.countryService = countryService;
        this.businessTransaction = new TransactionTemplate(transactionManager);
        this.businessTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.auditTransaction = new TransactionTemplate(transactionManager);
        this.auditTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.maxPhonesPerPackage = Math.max(1, maxPhonesPerPackage);
    }

    /** {@inheritDoc} */
    @Override
    public DataPackageImportResultVO importPhones(
            Long dataPackageId,
            DataPackageImportMode mode,
            MultipartFile file,
            Long createdBy) {
        requirePositiveId(dataPackageId);
        if (mode == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "导入模式不能为空");
        }
        String fileName = requireTxtFilename(file);
        DataPackageImport audit = createProcessingAudit(dataPackageId, mode, fileName, createdBy);
        try {
            ParsedDataPackagePhones parsed = parser.parse(readBytes(file));
            validateParsed(mode, parsed);
            Map<String, String> countries = resolveCountries(parsed.uniquePhones());
            validateAllowedCountries(countries);
            DataPackageImportResultVO result = businessTransaction.execute(status ->
                    importInTransaction(audit.getId(), dataPackageId, mode, parsed, countries));
            return Objects.requireNonNull(result, "数据包导入事务未返回结果");
        } catch (DuplicateKeyException exception) {
            BusinessException conflict = new BusinessException(
                    ErrorCode.CONFLICT, "数据包正在导入或号码发生并发冲突");
            recordFailure(audit.getId(), conflict);
            throw conflict;
        } catch (BusinessException exception) {
            recordFailure(audit.getId(), exception);
            throw exception;
        } catch (RuntimeException exception) {
            recordFailure(audit.getId(), exception);
            throw exception;
        }
    }

    private DataPackageImportResultVO importInTransaction(
            Long importId,
            Long dataPackageId,
            DataPackageImportMode mode,
            ParsedDataPackagePhones parsed,
            Map<String, String> countries) {
        DataPackage locked = packageMapper.selectActiveForUpdate(dataPackageId);
        if (locked == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "数据包不存在或已删除");
        }
        int currentGeneration = locked.getCurrentGeneration();
        int targetGeneration = mode == DataPackageImportMode.APPEND
                ? currentGeneration : currentGeneration + 1;
        if (importMapper.assignGeneration(
                importId, DataPackageImportStatus.PROCESSING.code(), targetGeneration) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据包正在导入或导入审计已超时");
        }
        return mode == DataPackageImportMode.APPEND
                ? append(importId, locked, parsed, countries)
                : overwrite(importId, locked, parsed, countries);
    }

    private DataPackageImportResultVO append(
            Long importId,
            DataPackage locked,
            ParsedDataPackagePhones parsed,
            Map<String, String> countries) {
        Set<String> existing = existingPhones(
                locked.getId(), locked.getCurrentGeneration(), parsed.uniquePhones());
        List<String> accepted = parsed.uniquePhones().stream()
                .filter(phone -> !existing.contains(phone))
                .toList();
        int currentCount = locked.getPhoneCount();
        ensureWithinPackageLimit(currentCount, accepted.size());
        long now = System.currentTimeMillis();
        insertPhones(locked.getId(), locked.getCurrentGeneration(), importId, accepted, countries, now);
        if (!accepted.isEmpty()) {
            requireSingleUpdate(packageMapper.incrementPhoneCount(
                    locked.getId(), locked.getCurrentGeneration(), accepted.size(), now));
            requireSingleUpdate(statMapper.incrementUnused(
                    locked.getId(), locked.getCurrentGeneration(), accepted.size(), now));
        }
        int duplicatedRows = parsed.duplicatedRows() + existing.size();
        markSuccess(importId, parsed, accepted.size(), duplicatedRows, now);
        log.info("数据包追加导入完成 packageId={} importId={} generation={} accepted={} duplicate={}",
                locked.getId(), importId, locked.getCurrentGeneration(), accepted.size(), duplicatedRows);
        return result(importId, DataPackageImportMode.APPEND, locked.getCurrentGeneration(),
                parsed, accepted.size(), duplicatedRows, currentCount + accepted.size());
    }

    private DataPackageImportResultVO overwrite(
            Long importId,
            DataPackage locked,
            ParsedDataPackagePhones parsed,
            Map<String, String> countries) {
        List<String> accepted = parsed.uniquePhones();
        ensureWithinPackageLimit(0, accepted.size());
        int oldGeneration = locked.getCurrentGeneration();
        int newGeneration = oldGeneration + 1;
        long now = System.currentTimeMillis();
        insertPhones(locked.getId(), newGeneration, importId, accepted, countries, now);
        requireSingleUpdate(packageMapper.switchGeneration(
                locked.getId(), oldGeneration, newGeneration, accepted.size(), now));
        requireSingleUpdate(statMapper.resetGeneration(
                locked.getId(), oldGeneration, newGeneration, accepted.size(), now));
        markSuccess(importId, parsed, accepted.size(), parsed.duplicatedRows(), now);
        log.info("数据包覆盖导入完成 packageId={} importId={} generation={} accepted={}",
                locked.getId(), importId, newGeneration, accepted.size());
        return result(importId, DataPackageImportMode.OVERWRITE, newGeneration,
                parsed, accepted.size(), parsed.duplicatedRows(), accepted.size());
    }

    private DataPackageImport createProcessingAudit(
            Long dataPackageId,
            DataPackageImportMode mode,
            String fileName,
            Long createdBy) {
        DataPackageImport audit = new DataPackageImport();
        audit.setDataPackageId(dataPackageId);
        audit.setImportMode(mode.code());
        audit.setStatus(DataPackageImportStatus.PROCESSING.code());
        audit.setSourceFileName(fileName);
        audit.setTotalRows(0);
        audit.setAcceptedRows(0);
        audit.setInvalidRows(0);
        audit.setDuplicatedRows(0);
        audit.setCreatedBy(createdBy);
        audit.setCreatedAt(System.currentTimeMillis());
        auditTransaction.executeWithoutResult(status -> importMapper.insert(audit));
        return audit;
    }

    private void markSuccess(
            Long importId,
            ParsedDataPackagePhones parsed,
            int acceptedRows,
            int duplicatedRows,
            long finishedAt) {
        int updated = importMapper.markSuccess(
                importId,
                DataPackageImportStatus.PROCESSING.code(),
                DataPackageImportStatus.SUCCESS.code(),
                parsed.totalRows(),
                acceptedRows,
                parsed.invalidRows(),
                duplicatedRows,
                finishedAt);
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据包导入审计状态已变化");
        }
    }

    private void recordFailure(Long importId, RuntimeException exception) {
        String reason = exception instanceof BusinessException
                ? exception.getMessage() : "导入处理失败";
        String safeReason = truncate(
                StringUtils.hasText(reason) ? reason.trim() : "导入处理失败",
                FAILURE_REASON_MAX_LENGTH);
        try {
            auditTransaction.executeWithoutResult(status -> importMapper.markFailed(
                    importId,
                    DataPackageImportStatus.PROCESSING.code(),
                    DataPackageImportStatus.FAILED.code(),
                    safeReason,
                    System.currentTimeMillis()));
        } catch (RuntimeException auditException) {
            log.warn("数据包失败审计更新失败 importId={}", importId, auditException);
        }
    }

    private void insertPhones(
            Long dataPackageId,
            int generation,
            Long importId,
            List<String> phones,
            Map<String, String> countries,
            long now) {
        for (int start = 0; start < phones.size(); start += INSERT_CHUNK_SIZE) {
            int end = Math.min(phones.size(), start + INSERT_CHUNK_SIZE);
            List<DataPackagePhone> rows = new ArrayList<>(end - start);
            for (String phone : phones.subList(start, end)) {
                DataPackagePhone row = new DataPackagePhone();
                row.setDataPackageId(dataPackageId);
                row.setGeneration(generation);
                row.setSourceImportId(importId);
                row.setPhone(phone);
                row.setCountryIso2(countries.get(phone));
                row.setPoolStatus(DataPackagePoolStatus.UNUSED.code());
                row.setCreatedAt(now);
                row.setUpdatedAt(now);
                rows.add(row);
            }
            if (!rows.isEmpty()) {
                phoneMapper.batchInsert(rows);
            }
        }
    }

    private Set<String> existingPhones(Long packageId, int generation, List<String> phones) {
        Set<String> existing = new HashSet<>();
        for (int start = 0; start < phones.size(); start += QUERY_CHUNK_SIZE) {
            int end = Math.min(phones.size(), start + QUERY_CHUNK_SIZE);
            existing.addAll(phoneMapper.selectExistingPhones(
                    packageId, generation, phones.subList(start, end)));
        }
        return existing;
    }

    private Map<String, String> resolveCountries(List<String> phones) {
        CountryService.PhonePrefixResolver resolver = countryService.activePhonePrefixResolver();
        Map<String, String> result = new HashMap<>();
        for (String phone : phones) {
            CountryOptionVO country = resolver.resolve(phone);
            result.put(phone, country == null ? null : country.iso2());
        }
        return result;
    }

    private static void validateAllowedCountries(Map<String, String> countries) {
        List<String> blocked = countries.values().stream()
                .filter(Objects::nonNull)
                .distinct()
                .filter(FORBIDDEN_IMPORT_COUNTRIES::containsKey)
                .sorted()
                .map(iso2 -> FORBIDDEN_IMPORT_COUNTRIES.get(iso2) + "（" + iso2 + "）")
                .toList();
        if (!blocked.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION,
                    "检测到禁止上传国家的号码：" + String.join("、", blocked) + "，请移除后再上传");
        }
    }

    private void ensureWithinPackageLimit(int currentCount, int increment) {
        long requested = (long) currentCount + increment;
        if (requested > maxPhonesPerPackage) {
            int available = Math.max(0, maxPhonesPerPackage - currentCount);
            throw new BusinessException(
                    ErrorCode.VALIDATION,
                    "单包最多 " + maxPhonesPerPackage + " 条号码，当前可追加余量 " + available);
        }
    }

    private static DataPackageImportResultVO result(
            Long importId,
            DataPackageImportMode mode,
            int generation,
            ParsedDataPackagePhones parsed,
            int acceptedRows,
            int duplicatedRows,
            int phoneCountAfterImport) {
        return new DataPackageImportResultVO(
                importId, mode, generation, parsed.totalRows(), acceptedRows,
                parsed.invalidRows(), duplicatedRows, phoneCountAfterImport);
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.VALIDATION, "号码文件读取失败");
        }
    }

    private static String requireTxtFilename(MultipartFile file) {
        if (file == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "号码 TXT 文件不能为空");
        }
        String name = file.getOriginalFilename();
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ErrorCode.VALIDATION, "号码文件名不能为空");
        }
        String normalized = name.trim();
        if (normalized.length() > FILE_NAME_MAX_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION, "号码文件名最长 255 个字符");
        }
        if (!normalized.toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) {
            throw new BusinessException(ErrorCode.VALIDATION, "号码文件必须为 UTF-8 .txt 文件");
        }
        return normalized;
    }

    private static void validateParsed(
            DataPackageImportMode mode,
            ParsedDataPackagePhones parsed) {
        if (parsed.totalRows() == 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "号码文件不能为空");
        }
        if (mode == DataPackageImportMode.OVERWRITE && parsed.uniquePhones().isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION, "覆盖导入至少需要一条合法号码");
        }
    }

    private static void requireSingleUpdate(int updated) {
        if (updated != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "数据包正在导入，请稍后重试");
        }
    }

    private static void requirePositiveId(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "数据包不存在或已删除");
        }
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
