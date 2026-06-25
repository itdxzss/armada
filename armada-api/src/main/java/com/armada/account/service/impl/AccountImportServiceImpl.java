package com.armada.account.service.impl;

import com.armada.account.mapper.AccountImportBatchMapper;
import com.armada.account.mapper.AccountImportDetailMapper;
import com.armada.account.model.dto.AccountImportDTO;
import com.armada.account.model.entity.AccountImportBatch;
import com.armada.account.model.entity.AccountImportDetail;
import com.armada.account.model.entity.ImportFormat;
import com.armada.account.model.entity.ImportResult;
import com.armada.account.model.entity.ParsedEntry;
import com.armada.account.model.vo.AccountImportBatchVO;
import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountImportParser;
import com.armada.account.service.AccountImportService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 账号导入业务实现。
 *
 * <p>流程:parser.parse → seenWid HashSet 批内去重 → 逐条分类 → 对成功行调
 * {@link AccountImportRowWriter#writeOne}(独立 Bean,确保 @Transactional 通过代理生效)→
 * 写 account_import_batch(status=2 已完成) → 批量写 account_import_detail → 回查返回 VO。</p>
 *
 * <p>单行三步原子写(account + account_state + account_credential)封装在 {@link AccountImportRowWriter}
 * 而非本类私有方法,规避 Spring 同类自调用导致 @Transactional 失效的 self-invocation 陷阱。</p>
 */
@Service
public class AccountImportServiceImpl implements AccountImportService {

    private static final Logger log = LoggerFactory.getLogger(AccountImportServiceImpl.class);

    private final AccountImportParser parser;
    private final AccountGroupService groupService;
    private final AccountImportRowWriter rowWriter;
    private final AccountImportBatchMapper batchMapper;
    private final AccountImportDetailMapper detailMapper;

    public AccountImportServiceImpl(AccountImportParser parser,
                                    AccountGroupService groupService,
                                    AccountImportRowWriter rowWriter,
                                    AccountImportBatchMapper batchMapper,
                                    AccountImportDetailMapper detailMapper) {
        this.parser = parser;
        this.groupService = groupService;
        this.rowWriter = rowWriter;
        this.batchMapper = batchMapper;
        this.detailMapper = detailMapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>计数口径:imported=SUCCESS;duplicate=批内重复∪DB uq 冲突;
     * formatError=FORMAT_ERROR∪CRED_INCOMPLETE(明细 parse_result 值区分 3/4)。
     * login_* 不写(NULL),step3 Kafka 回填。</p>
     */
    @Override
    public AccountImportBatchVO importAccounts(AccountImportDTO meta, byte[] fileBytes, String text) {
        ImportFormat format = ImportFormat.fromCode(meta.importFormat());

        List<ParsedEntry> entries = parser.parse(format, fileBytes, text);
        // 空 entries 或 parser 检测到「输入内容为空」(fileBytes/text 均空时 parser 产出该错误条目)
        if (entries.isEmpty() || isNoContentResult(entries)) {
            throw new BusinessException(ErrorCode.VALIDATION, "无可导入内容");
        }

        // 目标分组:明确传入则用,否则懒建系统默认分组
        Long resolvedGroupId = meta.accountGroupId() != null
                ? meta.accountGroupId()
                : groupService.ensureSystemGroup().getId();

        List<AccountImportDetail> details = new ArrayList<>(entries.size());
        Set<String> seenWid = new HashSet<>();
        int importedCount = 0;
        int duplicateCount = 0;
        int formatErrorCount = 0;

        long now = System.currentTimeMillis();
        int lineNo = 0;
        for (ParsedEntry entry : entries) {
            lineNo++;
            RowClassification classification = classify(entry, seenWid);
            ImportResult result = classification.result();
            Long accountId = null;
            String failReason = classification.failReason();

            if (result == ImportResult.SUCCESS) {
                // 三步原子写(DuplicateKeyException = 库内重复)
                try {
                    accountId = rowWriter.writeOne(
                            entry.getWid(),
                            entry,
                            resolvedGroupId,
                            meta.importFormat(),
                            meta.deviceOs(),
                            meta.accountType());
                    importedCount++;
                } catch (DuplicateKeyException e) {
                    result = ImportResult.DUPLICATE;
                    failReason = "库内已存在相同账号";
                    duplicateCount++;
                    log.info("[AccountImportService] 库内重复 maskPhone={}*** lineNo={}",
                            maskPhone(entry.getWid()), lineNo);
                }
            } else if (result == ImportResult.DUPLICATE) {
                duplicateCount++;
            } else {
                // FORMAT_ERROR 或 CRED_INCOMPLETE
                formatErrorCount++;
            }

            details.add(buildDetail(lineNo, entry.getWid(), accountId, result, failReason, now));
        }

        // 写批次行(status=2 已完成;login_* 不写=NULL)
        AccountImportBatch batch = buildBatch(meta, resolvedGroupId, entries.size(),
                importedCount, duplicateCount, formatErrorCount, now);
        batchMapper.insert(batch);

        // 批量写明细
        for (AccountImportDetail d : details) {
            d.setBatchId(batch.getId());
        }
        detailMapper.batchInsert(details);

        log.info("[AccountImportService] 导入完成 batchId={} total={} imported={} duplicate={} formatError={}",
                batch.getId(), entries.size(), importedCount, duplicateCount, formatErrorCount);

        // 回查批次行组装 VO
        AccountImportBatch saved = batchMapper.selectById(batch.getId());
        return toVO(saved);
    }

    // ---- 私有方法 ----

    /**
     * 判断 entries 是否为「无内容」:parser 对空 fileBytes/text 产出一条「输入内容为空」错误条目。
     * 这类情况应整批拒绝,而非当成 1 行格式错误写批次。
     */
    private boolean isNoContentResult(List<ParsedEntry> entries) {
        if (entries.size() != 1) {
            return false;
        }
        String err = entries.get(0).getParseError();
        return err != null && err.contains("输入内容为空");
    }

    /**
     * 按 parseError 和 seenWid 分类单条 entry。
     * seenWid 在本方法内 add,批内重复的第二次出现才返回 DUPLICATE。
     */
    private RowClassification classify(ParsedEntry entry, Set<String> seenWid) {
        String parseError = entry.getParseError();
        if (parseError != null && parseError.contains("凭据不全")) {
            return new RowClassification(ImportResult.CRED_INCOMPLETE, parseError);
        }
        if (parseError != null) {
            return new RowClassification(ImportResult.FORMAT_ERROR, parseError);
        }
        String wid = entry.getWid();
        if (wid == null || !seenWid.add(wid)) {
            return new RowClassification(ImportResult.DUPLICATE, "批内重复号码");
        }
        return new RowClassification(ImportResult.SUCCESS, null);
    }

    private AccountImportDetail buildDetail(int lineNo, String wsPhone, Long accountId,
                                             ImportResult result, String failReason, long now) {
        AccountImportDetail d = new AccountImportDetail();
        d.setLineNo(lineNo);
        d.setWsPhone(wsPhone);
        d.setAccountId(accountId);
        d.setParseResult(result.getCode());
        d.setFailReason(failReason);
        // loginResult 不写(NULL=未登录/步骤1)
        d.setCreatedAt(now);
        return d;
    }

    private AccountImportBatch buildBatch(AccountImportDTO meta, Long groupId,
                                           int total, int imported, int duplicate,
                                           int formatError, long now) {
        AccountImportBatch b = new AccountImportBatch();
        b.setAccountGroupId(groupId);
        b.setBatchName(meta.batchName());
        b.setImportFormat(meta.importFormat());
        b.setDeviceOs(meta.deviceOs());
        b.setAccountType(meta.accountType());
        b.setIpRegion(meta.ipRegion());
        b.setTotalRows(total);
        b.setImportedRows(imported);
        b.setDuplicateRows(duplicate);
        b.setFormatErrorRows(formatError);
        // login_* 不写(step1=NULL)
        b.setStatus(2);   // 已完成;step1 同步导入即结束
        b.setCreatedAt(now);
        return b;
    }

    private AccountImportBatchVO toVO(AccountImportBatch b) {
        return new AccountImportBatchVO(
                b.getId(),
                b.getBatchName(),
                b.getSourceFileName(),
                b.getImportFormat(),
                b.getDeviceOs(),
                b.getAccountType(),
                b.getIpRegion(),
                b.getTotalRows(),
                b.getImportedRows(),
                b.getDuplicateRows(),
                b.getFormatErrorRows(),
                b.getLoginSuccess(),
                b.getLoginFailed(),
                b.getLoginAbnormal(),
                b.getStatus(),
                b.getCreatedAt()
        );
    }

    /** 日志脱敏:保留前缀,后 4 位用 *** 替换。 */
    private String maskPhone(String wid) {
        if (wid == null || wid.length() <= 4) {
            return "****";
        }
        return wid.substring(0, wid.length() - 4) + "***";
    }

    /** 单行分类结果内部载体(不抽公共类,无其他调用点)。 */
    private record RowClassification(ImportResult result, String failReason) {
    }
}
