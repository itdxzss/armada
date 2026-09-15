package com.armada.resource.service.impl;

import com.armada.resource.mapper.GroupDataPackageMapper;
import com.armada.resource.mapper.GroupDataPackagePhoneMapper;
import com.armada.resource.mapper.GroupDataPackageStatMapper;
import com.armada.resource.model.entity.GroupDataPackage;
import com.armada.resource.model.entity.GroupDataPackagePhone;
import com.armada.resource.model.entity.GroupDataPackageImport;
import com.armada.resource.model.vo.GroupDataPackageImportResultVO;
import com.armada.resource.service.GroupDataPackageFileParser.ParsedFile;
import com.armada.resource.service.GroupDataPackageFileParser.ParsedPhone;
import com.armada.platform.country.service.CountryService;
import com.armada.task.service.GroupDataPackageTaskProjectionService;
import com.armada.platform.country.model.vo.CountryOptionVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 单次导入事务：包锁、分块去重、写入、切代及审计一起提交。 */
@Service
public class GroupDataPackageImportWriter {
    /** 单包安全容量，不预留通用配额框架。 */
    private static final int MAX_PACKAGE_PHONES = 500_000;
    private static final int SQL_CHUNK = 500;
    static final int APPEND = 1, OVERWRITE = 2;
    private final GroupDataPackageMapper packages;
    private final GroupDataPackagePhoneMapper phones;
    private final GroupDataPackageStatMapper stats;
    private final GroupDataPackageTaskProjectionService projection;
    private final GroupDataPackageImportAuditService audit;

    /** 注入当前资源和国家业务服务。 */
    public GroupDataPackageImportWriter(GroupDataPackageMapper packages, GroupDataPackagePhoneMapper phones,
            GroupDataPackageStatMapper stats, GroupDataPackageTaskProjectionService projection, GroupDataPackageImportAuditService audit) {
        this.packages = packages; this.phones = phones; this.stats = stats;
        this.projection = projection; this.audit = audit;
    }

    /** 校验租户资源后才允许建立导入审计。 */
    public void requireAccessible(long packageId) {
        if (packages.selectActive(packageId) == null) { throw missing(); }
    }

    /** 失败回滚不改变当前包和号码集合。 */
    @Transactional(rollbackFor = Exception.class)
    public GroupDataPackageImportResultVO write(ImportJob job) {
        GroupDataPackageImport entry = job.audit();
        GroupDataPackage parent = packages.lockActive(entry.getPackageId());
        if (parent == null) { throw missing(); }
        boolean overwrite = entry.getMode() == OVERWRITE;
        if (overwrite) { projection.assertNotActivelyUsed(parent.getId()); }
        if (overwrite && phones.activeCount(parent.getId()) > 0) { throw invalid("仍有占用或待确认号码，不能覆盖"); }
        int generation = parent.getGeneration() + (overwrite ? 1 : 0);
        entry.setGeneration(generation);
        Map<String, GroupDataPackagePhone> existing = overwrite ? Map.of() : existing(parent, job.parsed());
        Set<String> rejected = privacyHistory(job.parsed(), job.privacyDays());
        List<ParsedPhone> accepted = selectAccepted(job.parsed(), existing, rejected);
        int oldCount = overwrite ? 0 : Math.toIntExact(stats.select(parent.getId()).getTotalCount());
        if ((long) oldCount + accepted.size() > MAX_PACKAGE_PHONES) { throw invalid("单包最多500000个号码"); }
        if (overwrite && accepted.isEmpty()) { throw invalid("覆盖文件没有有效可用号码，原包保持不变"); }
        int sequence = overwrite ? 0 : phones.maxSeq(parent.getId(), generation);
        insert(job, accepted, sequence);
        if (overwrite) {
            if (packages.switchGeneration(parent.getId(), parent.getGeneration(), System.currentTimeMillis()) != 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "数据包版本已变化");
            }
            stats.reset(parent.getId(), generation);
        }
        stats.addImported(parent.getId(), accepted.size());
        refreshCountry(parent.getId(), generation, job.countryOptions());
        int existingCount = (int) job.parsed().phones().stream().filter(p -> existing.containsKey(p.phone())).count();
        int privacyCount = (int) job.parsed().phones().stream()
                .filter(p -> !existing.containsKey(p.phone()) && rejected.contains(p.phone())).count();
        entry.setTotalRows(job.parsed().totalRows()); entry.setAcceptedRows(accepted.size());
        entry.setInvalidRows(job.parsed().invalidRows()); entry.setDuplicatedRows(job.parsed().duplicatedRows() + existingCount);
        entry.setPrivacyFilteredRows(privacyCount); entry.setFinishedAt(System.currentTimeMillis());
        audit.succeed(entry);
        return new GroupDataPackageImportResultVO(entry.getId(), overwrite ? "overwrite" : "append", generation,
                entry.getTotalRows(), accepted.size(), entry.getInvalidRows(), entry.getDuplicatedRows(),
                privacyCount, oldCount + accepted.size());
    }

    private Map<String, GroupDataPackagePhone> existing(GroupDataPackage parent, ParsedFile parsed) {
        List<String> numbers = parsed.phones().stream().map(ParsedPhone::phone).toList();
        Map<String, GroupDataPackagePhone> result = new HashMap<>();
        for (int start = 0; start < numbers.size(); start += SQL_CHUNK) {
            var chunk = numbers.subList(start, Math.min(start + SQL_CHUNK, numbers.size()));
            phones.existing(parent.getId(), parent.getGeneration(), chunk).forEach(row -> result.put(row.getPhone(), row));
        }
        return result;
    }

    private Set<String> privacyHistory(ParsedFile parsed, int days) {
        if (days == 0) { return Set.of(); }
        long cutoff = System.currentTimeMillis() - Duration.ofDays(days).toMillis();
        List<String> numbers = parsed.phones().stream().map(ParsedPhone::phone).toList();
        Set<String> rejected = new HashSet<>();
        for (int start = 0; start < numbers.size(); start += SQL_CHUNK) {
            rejected.addAll(phones.privacyHistory(numbers.subList(start, Math.min(start + SQL_CHUNK, numbers.size())), cutoff));
        }
        rejected.addAll(projection.privacyRejectedPhones(numbers, cutoff));
        return rejected;
    }

    private List<ParsedPhone> selectAccepted(ParsedFile parsed,
            Map<String, GroupDataPackagePhone> existing, Set<String> rejected) {
        List<Long> promote = new ArrayList<>();
        List<ParsedPhone> accepted = new ArrayList<>();
        for (ParsedPhone phone : parsed.phones()) {
            GroupDataPackagePhone prior = existing.get(phone.phone());
            if (prior != null) {
                if (phone.adminRequired() && !Boolean.TRUE.equals(prior.getAdminRequired())) { promote.add(prior.getId()); }
            } else if (!rejected.contains(phone.phone())) { accepted.add(phone); }
        }
        for (int start = 0; start < promote.size(); start += SQL_CHUNK) {
            phones.promoteAdmin(promote.subList(start, Math.min(start + SQL_CHUNK, promote.size())));
        }
        return accepted;
    }

    private void insert(ImportJob job, List<ParsedPhone> accepted, int startSequence) {
        GroupDataPackageImport entry = job.audit();
        CountryService.PhonePrefixResolver resolver = job.resolver();
        long now = System.currentTimeMillis();
        for (int start = 0; start < accepted.size(); start += SQL_CHUNK) {
            List<GroupDataPackagePhone> rows = new ArrayList<>();
            int end = Math.min(start + SQL_CHUNK, accepted.size());
            for (int index = start; index < end; index++) {
                ParsedPhone parsed = accepted.get(index);
                CountryOptionVO country = resolver.resolve(parsed.phone());
                GroupDataPackagePhone row = new GroupDataPackagePhone();
                row.setPackageId(entry.getPackageId()); row.setGeneration(entry.getGeneration());
                row.setSourceImportId(entry.getId()); row.setPhone(parsed.phone());
                row.setCountryIso2(country == null ? null : country.iso2());
                row.setMemberSeq(startSequence + index + 1); row.setSourceLineNo(parsed.sourceLineNo());
                row.setAdminRequired(parsed.adminRequired()); row.setCreatedAt(now); row.setUpdatedAt(now);
                rows.add(row);
            }
            phones.insertBatch(rows);
        }
    }

    private void refreshCountry(long id, int generation, Map<String, CountryOptionVO> options) {
        String primary = phones.primaryCountry(id, generation);
        CountryOptionVO option = options.get(primary);
        stats.country(id, primary, option == null ? null : option.continentCode());
    }

    private static BusinessException missing() { return new BusinessException(ErrorCode.NOT_FOUND, "数据包不存在或已删除"); }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION, message); }
    /** 已预检的导入内容和可靠历史筛选窗口。 */
    public record ImportJob(GroupDataPackageImport audit, ParsedFile parsed, int privacyDays,
            CountryService.PhonePrefixResolver resolver, Map<String, CountryOptionVO> countryOptions) { }
}
