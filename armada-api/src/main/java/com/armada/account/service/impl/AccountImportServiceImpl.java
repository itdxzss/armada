package com.armada.account.service.impl;

import com.armada.account.converter.AccountConverter;
import com.armada.account.mapper.AccountImportBatchMapper;
import com.armada.account.mapper.AccountImportDetailMapper;
import com.armada.account.model.dto.AccountImportDTO;
import com.armada.account.model.dto.AccountImportDetailQuery;
import com.armada.account.model.dto.AccountImportQuery;
import com.armada.account.model.enums.SourceFileType;
import com.armada.account.model.entity.AccountImportBatch;
import com.armada.account.model.entity.AccountImportDetail;
import com.armada.account.model.entity.AccountImportOnlinePhase;
import com.armada.account.model.entity.ImportFormat;
import com.armada.account.model.entity.ImportResult;
import com.armada.account.model.entity.ParsedEntry;
import com.armada.account.model.vo.AccountImportBatchListVO;
import com.armada.account.model.vo.AccountImportBatchVO;
import com.armada.account.model.vo.AccountImportBatchVoRow;
import com.armada.account.model.vo.AccountImportDetailVO;
import com.armada.account.model.vo.AccountImportDetailVoRow;
import com.armada.account.model.vo.AccountImportExportFile;
import com.armada.account.model.vo.AccountImportExportRow;
import com.armada.account.model.vo.AccountImportLoginStatsRow;
import com.armada.account.service.AccountGroupService;
import com.armada.account.service.AccountImportParser;
import com.armada.account.service.AccountImportService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 账号导入业务实现。
 *
 * <p>流程:前置校验(format/accountType/分组存在) → parser.parse → 空内容拒
 * → <b>先 insert 批次行(审计锚点,total 已知、三计数先 0)</b> → seenWid HashSet 批内去重 → 逐条分类
 * → 对成功行调 {@link AccountImportRowWriter#writeOne}(独立 Bean,确保 @Transactional 通过代理生效)
 * → batchMapper.updateCounts 回填三计数 → 批量写 account_import_detail → 回查返回 VO。</p>
 *
 * <p>审计锚点先于账号入库的好处:校验失败前置即拒,账号一条不写;
 * 批次行已存在后续任意意外也不会产生「无审计孤儿账号」。</p>
 *
 * <p>单行三步原子写(account + account_state + account_credential)封装在 {@link AccountImportRowWriter}
 * 而非本类私有方法,规避 Spring 同类自调用导致 @Transactional 失效的 self-invocation 陷阱。</p>
 */
@Service
public class AccountImportServiceImpl implements AccountImportService {

    private static final Logger log = LoggerFactory.getLogger(AccountImportServiceImpl.class);

    /** 批次状态:已完成(step1 同步导入即结束,成败不进 status、看计数列)。 */
    private static final int BATCH_STATUS_DONE = 2;

    /** source_file_name 兜底值:纯文本粘贴时无文件名,用此常量串标识来源,保证标识列非空。 */
    private static final String SOURCE_FILE_DEFAULT = "导入";

    private final AccountImportParser parser;
    private final AccountGroupService groupService;
    private final AccountImportRowWriter rowWriter;
    private final AccountImportBatchMapper batchMapper;
    private final AccountImportDetailMapper detailMapper;
    private final AccountConverter converter;

    public AccountImportServiceImpl(AccountImportParser parser,
                                    AccountGroupService groupService,
                                    AccountImportRowWriter rowWriter,
                                    AccountImportBatchMapper batchMapper,
                                    AccountImportDetailMapper detailMapper,
                                    AccountConverter converter) {
        this.parser = parser;
        this.groupService = groupService;
        this.rowWriter = rowWriter;
        this.batchMapper = batchMapper;
        this.detailMapper = detailMapper;
        this.converter = converter;
    }

    /**
     * 导入账号:把上传文件 / 粘贴文本解析成多条账号,逐条分类后对合格条目做「三步原子写」,
     * 最后落一条批次汇总 + 每条明细,返回批次结果。(接口契约见 {@link AccountImportService#importAccounts})
     *
     * <p>整体流程:</p>
     * <ol>
     *   <li>必填校验(importFormat/accountType)+ 目标分组存在性校验(accountGroupId 非 null 时)。</li>
     *   <li>按格式解析条目列表;内容为空直接拒。</li>
     *   <li><b>先 insert 批次行</b>(total 已知,三计数先 0),拿到 batch.id —— 审计锚点先于账号入库。</li>
     *   <li>逐条分类并处理:<b>凭据不全 / 格式错误 / 批内同号重复</b> → 只记一条失败明细、不建号;
     *       <b>合格条目</b> → 调 {@link AccountImportRowWriter#writeOne} 在一个事务里写三张表
     *       (account 身份行 + account_state 默认行 + account_credential 凭据);
     *       若撞库内唯一键(并发下同号已存在)记为「重复」。</li>
     *   <li>updateCounts 回填三计数 + 批量插明细 + 回查 VO 返回。</li>
     * </ol>
     *
     * <p>三个计数列的口径:</p>
     * <ul>
     *   <li>{@code importedRows} = 成功入库条数;</li>
     *   <li>{@code duplicateRows} = 重复条数 = 批内同号重复 + 库内已存在(并发撞唯一键);</li>
     *   <li>{@code formatErrorRows} = 不合格条数 = 格式错误 + 凭据不全(明细 {@code parseResult} 用 3 / 4 区分);</li>
     * </ul>
     * <p>登录相关计数(loginSuccess / failed / abnormal)step1 不写(NULL),留 step3 接协议层 Kafka 回填。</p>
     *
     * @param meta      导入元信息(格式/类型/分组/来源文件名等)
     * @param fileBytes 文件字节(文件导入时);文本粘贴时为 null
     * @param text      粘贴文本(文本导入时);文件导入时为 null
     * @return 批次结果 VO(含计数、状态)
     * @throws BusinessException 当参数不合法、分组不存在、内容为空时抛 VALIDATION 或 NOT_FOUND
     */
    @Override
    public AccountImportBatchVO importAccounts(AccountImportDTO meta, byte[] fileBytes, String text) {
        // 必填字段前置校验:importFormat/accountType 为 null 时拆箱会 NPE
        if (meta.importFormat() == null || meta.accountType() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "导入格式/账号类型不能为空");
        }
        ImportFormat format = ImportFormat.fromCode(meta.importFormat());

        // 目标分组:明确传入则校验存在,否则懒建系统默认分组
        Long resolvedGroupId = meta.accountGroupId() != null
                ? groupService.requireExisting(meta.accountGroupId()).getId()
                : groupService.ensureSystemGroup().getId();

        List<ParsedEntry> entries = parser.parse(format, fileBytes, text);
        // 空 entries 或 parser 检测到「输入内容为空」(fileBytes/text 均空时 parser 产出该错误条目)
        if (entries.isEmpty() || isNoContentResult(entries)) {
            throw new BusinessException(ErrorCode.VALIDATION, "无可导入内容");
        }

        long now = System.currentTimeMillis();   // 本批统一时间戳(批次/明细/账号行共用,epoch 毫秒)
        String sourceFileType = resolveSourceFileType(fileBytes, text);

        // 审计锚点先行:批次行在任何账号入库前已存在;total 已知,三计数先写 0 循环后回填。
        // login_* step1 不写=NULL,留 step3 回填。insert 后自增 id 回填到 batch.id。
        AccountImportBatch batch = buildBatch(meta, resolvedGroupId, entries.size(), now, sourceFileType);
        batchMapper.insert(batch);

        List<AccountImportDetail> details = new ArrayList<>(entries.size());
        Set<String> seenWid = new HashSet<>();   // 批内去重集合:本批已出现过的 wid(手机号)
        int importedCount = 0;                   // 成功入库
        int duplicateCount = 0;                  // 重复(批内 + 库内)
        int formatErrorCount = 0;                // 不合格(格式错误 + 凭据不全)

        int lineNo = 0;
        // 逐条:先分类,只有合格条目才落库;无论成败都为每条收集一行明细 + 累计对应计数
        for (ParsedEntry entry : entries) {
            lineNo++;
            // classify 内部判定:parseError 含"凭据不全"→CRED_INCOMPLETE;其它 parseError→FORMAT_ERROR;
            // wid 已在 seenWid→DUPLICATE(批内重复);否则 SUCCESS 并把 wid 记入 seenWid(防本批后续同号)
            RowClassification classification = classify(entry, seenWid);
            ImportResult result = classification.result();
            Long accountId = null;
            String failReason = classification.failReason();

            if (result == ImportResult.SUCCESS) {
                // 合格条目:三表原子写(account + account_state + account_credential 在 writeOne 的同一事务里);
                // 撞 DB 唯一键 uq_tenant_phone(并发下同号已先入库)→ 整行回滚,本条改记「库内重复」
                try {
                    accountId = rowWriter.writeOne(entry.getWid(), entry, resolvedGroupId, meta);
                    importedCount++;
                } catch (DuplicateKeyException e) {
                    result = ImportResult.DUPLICATE;
                    failReason = "库内已存在相同账号";
                    duplicateCount++;
                    log.info("[AccountImportService] 库内重复 maskPhone={}*** lineNo={}",
                            maskPhone(entry.getWid()), lineNo);
                }
            } else if (result == ImportResult.DUPLICATE) {
                // 批内重复(同号在本批前面已出现过):不建号,只计数
                duplicateCount++;
            } else {
                // 解析阶段即判失败(FORMAT_ERROR 格式错误 / CRED_INCOMPLETE 凭据不全):不建号,只计数
                formatErrorCount++;
            }

            // 无论成败,每条都落一行明细(失败行 accountId 为 null,failReason 带原因供前端展示)
            AccountImportDetail detail = buildDetail(lineNo, entry, accountId,
                    new RowClassification(result, failReason), now);
            detail.setBatchId(batch.getId());
            details.add(detail);
        }

        // 回填三计数(循环完成后实际数值已知)
        batchMapper.updateCounts(batch.getId(), importedCount, duplicateCount, formatErrorCount);

        // 批量插明细
        detailMapper.batchInsert(details);

        log.info("[AccountImportService] 导入完成 batchId={} total={} imported={} duplicate={} formatError={}",
                batch.getId(), entries.size(), importedCount, duplicateCount, formatErrorCount);

        // 回查批次行组装 VO(三计数已由 updateCounts 更新)
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

    private AccountImportDetail buildDetail(int lineNo, ParsedEntry entry,
                                             Long accountId, RowClassification cls, long now) {
        AccountImportDetail d = new AccountImportDetail();
        d.setLineNo(lineNo);
        d.setWsPhone(entry.getWid());
        d.setRawPayload(entry.getRawPayload());
        d.setSourceEntryName(entry.getSourceEntryName() != null
                ? entry.getSourceEntryName()
                : "line-" + lineNo);
        d.setAccountId(accountId);
        d.setParseResult(cls.result().getCode());
        d.setFailReason(cls.failReason());
        // loginResult 不写(NULL=未登录/步骤1)
        d.setOnlinePhase(cls.result() == ImportResult.SUCCESS
                ? AccountImportOnlinePhase.QUEUED
                : AccountImportOnlinePhase.SKIPPED);
        d.setCreatedAt(now);
        return d;
    }

    /**
     * 构建批次 shell 行(total 已知、三计数先 0)。
     * 三计数由循环结束后的 updateCounts 回填。
     *
     * <p>source_file_name 作为来源标识:文件导入用原始文件名;纯文本粘贴无文件名时
     * 兜底为 {@link #SOURCE_FILE_DEFAULT} 常量串,保证标识列非空。</p>
     *
     * @param meta    导入元信息
     * @param groupId 已解析的目标分组 ID
     * @param total   解析总行数
     * @param now     本批统一时间戳(epoch 毫秒)
     * @param sourceFileType 原始导入容器类型
     * @return 待 insert 的批次实体(三计数均为 0)
     */
    private AccountImportBatch buildBatch(AccountImportDTO meta,
                                          Long groupId,
                                          int total,
                                          long now,
                                          String sourceFileType) {
        AccountImportBatch b = new AccountImportBatch();
        b.setAccountGroupId(groupId);
        b.setSourceFileName(StringUtils.hasText(meta.sourceFileName())
                ? meta.sourceFileName() : SOURCE_FILE_DEFAULT);
        b.setSourceFileType(sourceFileType);
        b.setImportFormat(meta.importFormat());
        b.setDeviceOs(meta.deviceOs());
        b.setAccountType(meta.accountType());
        b.setIpRegion(meta.ipRegion());
        b.setTotalRows(total);
        b.setImportedRows(0);
        b.setDuplicateRows(0);
        b.setFormatErrorRows(0);
        // login_* 不写(step1=NULL)
        b.setStatus(BATCH_STATUS_DONE);   // 已完成;step1 同步导入即结束
        b.setCreatedAt(now);
        return b;
    }

    private String resolveSourceFileType(byte[] fileBytes, String text) {
        if (text != null && !text.isEmpty()) {
            return SourceFileType.TXT;
        }
        return isZipBytes(fileBytes) ? SourceFileType.ZIP : SourceFileType.TXT;
    }

    /** 判断字节数组是否为 ZIP 文件(Magic bytes: PK 0x50 0x4B)。 */
    private boolean isZipBytes(byte[] bytes) {
        return bytes != null && bytes.length >= 2 && bytes[0] == 0x50 && bytes[1] == 0x4B;
    }

    private AccountImportBatchVO toVO(AccountImportBatch b) {
        return new AccountImportBatchVO(
                b.getId(),
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

    /** {@inheritDoc} */
    @Override
    public PageResult<AccountImportBatchListVO> listBatches(AccountImportQuery query) {
        long total = batchMapper.countPage(query);
        List<AccountImportBatchVoRow> rows = batchMapper.selectPage(query);
        applyLoginStats(rows);
        List<AccountImportBatchListVO> list = converter.toBatchListVOList(rows);
        return PageResult.of(list, query.getPage(), query.getPageSize(), total);
    }

    private void applyLoginStats(List<AccountImportBatchVoRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<Long> batchIds = rows.stream()
                .map(AccountImportBatchVoRow::getId)
                .toList();
        Map<Long, AccountImportLoginStatsRow> statsByBatchId = new HashMap<>();
        for (AccountImportLoginStatsRow stats : detailMapper.selectLoginStatsByBatchIds(batchIds)) {
            statsByBatchId.put(stats.getBatchId(), stats);
        }
        for (AccountImportBatchVoRow row : rows) {
            AccountImportLoginStatsRow stats = statsByBatchId.get(row.getId());
            row.setLoginSuccess(stats == null ? 0 : valueOrZero(stats.getLoginSuccess()));
            row.setLoginFailed(stats == null ? 0 : valueOrZero(stats.getLoginFailed()));
            row.setLoginAbnormal(stats == null ? 0 : valueOrZero(stats.getLoginAbnormal()));
        }
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * {@inheritDoc}
     *
     * <p>parseResultLabel 在 Service 层填充(VoRow 从 DB 取 parseResult 整型),避免 Mapper 层承担业务翻译。</p>
     */
    @Override
    public PageResult<AccountImportDetailVO> listDetails(AccountImportDetailQuery query) {
        if (query.getBatchId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "batchId 不能为空");
        }
        long total = detailMapper.countByBatch(query);
        List<AccountImportDetailVoRow> rows = detailMapper.selectPageByBatch(query);
        List<AccountImportDetailVO> vos = rows.stream()
                .map(this::toDetailVO)
                .toList();
        return PageResult.of(vos, query.getPage(), query.getPageSize(), total);
    }

    /**
     * {@inheritDoc}
     *
     * <p>按新增批次保存的原始容器类型恢复 ZIP/TXT。历史批次缺少原始材料时返回业务错误。</p>
     */
    @Override
    public AccountImportExportFile exportDetails(Long batchId, String scope) {
        if (batchId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "batchId 不能为空");
        }
        AccountImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "导入批次不存在");
        }
        String sourceFileType = SourceFileType.requireSupported(batch.getSourceFileType());
        String resolvedScope = (scope == null || scope.isBlank()) ? "all" : scope;
        List<AccountImportExportRow> rows = detailMapper.selectExportRowsByBatch(batchId, resolvedScope);
        ensureExportRowsHavePayload(rows);

        if (SourceFileType.ZIP.equals(sourceFileType)) {
            return new AccountImportExportFile(
                    "account-import-" + batchId + "-" + resolvedScope + ".zip",
                    "application/zip",
                    buildZipExport(rows));
        }
        return new AccountImportExportFile(
                "account-import-" + batchId + "-" + resolvedScope + ".txt",
                "text/plain;charset=UTF-8",
                buildTextExport(rows));
    }

    /** 将 AccountImportDetailVoRow 转换为 AccountImportDetailVO(填充 parseResultLabel)。 */
    private AccountImportDetailVO toDetailVO(AccountImportDetailVoRow r) {
        return new AccountImportDetailVO(
                r.getId(),
                r.getLineNo(),
                r.getWsPhone(),
                r.getAccountId(),
                r.getParseResult(),
                resolveLabel(r.getParseResult()),
                r.getFailReason(),
                r.getLoginResult(),
                r.getCreatedAt(),
                r.getGroupName()
        );
    }

    /**
     * 按 parse_result 整型值解析中文标签;未匹配返回「未知」防止空串。
     */
    private String resolveLabel(int parseResult) {
        ImportResult result = ImportResult.fromCode(parseResult);
        return result != null ? result.getLabel() : "未知";
    }

    /**
     * 校验导出行是否具备原始 payload。历史批次或异常数据缺失时按业务错误返回,
     * 避免导出一个看似成功但内容不可恢复的文件。
     */
    private void ensureExportRowsHavePayload(List<AccountImportExportRow> rows) {
        for (AccountImportExportRow row : rows) {
            if (row.getRawPayload() == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "该批次缺少原始导出材料");
            }
        }
    }

    /**
     * 构造 TXT 导出:按行号顺序拼接原始 payload,行之间用单个换行分隔。
     */
    private byte[] buildTextExport(List<AccountImportExportRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(rows.get(i).getRawPayload());
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 构造 ZIP 导出:一条明细一个 entry,内容为对应 rawPayload 的 UTF-8 字节。
     */
    private byte[] buildZipExport(List<AccountImportExportRow> rows) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            LinkedHashSet<String> usedNames = new LinkedHashSet<>();
            for (AccountImportExportRow row : rows) {
                String entryName = uniqueEntryName(resolveEntryName(row), usedNames);
                zos.putNextEntry(new ZipEntry(entryName));
                zos.write(row.getRawPayload().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            zos.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.VALIDATION, "导出文件生成失败");
        }
    }

    /**
     * 解析 ZIP entry 名。优先使用原始来源名;缺失时用 lineNo + 手机号生成稳定兜底名。
     */
    private String resolveEntryName(AccountImportExportRow row) {
        if (StringUtils.hasText(row.getSourceEntryName())) {
            String name = row.getSourceEntryName();
            return name.endsWith(".json") ? name : name + ".json";
        }
        String phone = StringUtils.hasText(row.getWsPhone()) ? row.getWsPhone() : "unknown";
        return "line-" + row.getLineNo() + "-" + phone + ".json";
    }

    /**
     * ZIP 不允许重复 entry 名。发生重复时追加递增后缀,保持导出文件可正常打开。
     */
    private String uniqueEntryName(String preferredName, LinkedHashSet<String> usedNames) {
        if (usedNames.add(preferredName)) {
            return preferredName;
        }
        int dot = preferredName.lastIndexOf('.');
        String base = dot >= 0 ? preferredName.substring(0, dot) : preferredName;
        String ext = dot >= 0 ? preferredName.substring(dot) : "";
        int index = 2;
        while (true) {
            String candidate = base + "-" + index + ext;
            if (usedNames.add(candidate)) {
                return candidate;
            }
            index++;
        }
    }
}
