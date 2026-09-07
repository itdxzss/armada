package com.armada.account.service.impl;

import com.armada.account.mapper.AccountImportDetailMapper;
import com.armada.account.model.dto.AccountImportDTO;
import com.armada.account.model.dto.DeviceImportDTO;
import com.armada.account.model.dto.DeviceImportDefaults;
import com.armada.account.model.entity.AccountImportOnlinePhase;
import com.armada.account.model.entity.ImportResult;
import com.armada.account.model.entity.ParsedEntry;
import com.armada.account.model.vo.AccountImportBatchVO;
import com.armada.account.model.vo.DeviceImportVO;
import com.armada.account.service.AccountImportParser;
import com.armada.account.service.AccountImportService;
import com.armada.account.service.DeviceImportService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 原子复用批量导入服务；手机号冲突或任意落库失败时回滚整个手机请求。 */
@Service
public class DeviceImportServiceImpl implements DeviceImportService {

    private static final Pattern PHONE = Pattern.compile("[0-9]{7,15}");
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");
    private static final String QUEUED = "QUEUED";
    private static final String WAITING_LOGOUT = "WAITING_LOGOUT";
    private static final String DEVICE_SOURCE = "device-import";
    private static final int SINGLE_ROW = 1;

    private final AccountImportParser parser;
    private final AccountImportService imports;
    private final AccountImportDetailMapper details;

    /** 注入现有解析、导入和明细查询，不另建凭据写入或调度通道。 */
    public DeviceImportServiceImpl(AccountImportParser parser, AccountImportService imports,
                                   AccountImportDetailMapper details) {
        this.parser = parser;
        this.imports = imports;
        this.details = details;
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public DeviceImportVO importAccount(DeviceImportDTO request, DeviceImportDefaults defaults) {
        if (defaults == null || !Objects.equals(TenantContext.get(), defaults.tenantId())) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        ParsedEntry entry = validate(request);
        if (details.existsPendingByPhone(request.phone(), ImportResult.SUCCESS.getCode(),
                AccountImportOnlinePhase.QUEUED, AccountImportOnlinePhase.DISPATCHED,
                AccountImportOnlinePhase.WAITING_LOGOUT)) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        AccountImportDTO configured = defaults.metadata();
        AccountImportDTO metadata = new AccountImportDTO(request.accountGroupId(), configured.importFormat(),
                configured.deviceOs(), configured.accountType(), configured.ipRegion(), configured.ipAllocationMode(),
                configured.remark(), configured.sourceFileName());
        // 共用导入服务在写批次前通过租户插件复核目标分组，不退回系统默认分组。
        AccountImportBatchVO batch = imports.importDeviceAccount(metadata, entry);
        // RowWriter 冲突会标记参与事务 rollback-only，必须抛业务异常退出，不能正常返回再提交。
        if (batch.duplicateRows() > 0) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        if (batch.totalRows() != SINGLE_ROW || batch.importedRows() != SINGLE_ROW
                || batch.formatErrorRows() != 0) {
            throw new BusinessException(ErrorCode.VALIDATION);
        }
        // 外层事务提交前完成挂起；调度器不会读到短暂的 QUEUED。
        if (details.holdDeviceImport(batch.id(), AccountImportOnlinePhase.QUEUED,
                AccountImportOnlinePhase.WAITING_LOGOUT) != SINGLE_ROW) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        return new DeviceImportVO(batch.id(), WAITING_LOGOUT);
    }

    /** {@inheritDoc} */
    @Override
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public DeviceImportVO confirmLogout(Long batchId) {
        if (TenantContext.get() == null || batchId == null || batchId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION);
        }
        var phases = details.selectDeviceHandoffPhasesForUpdate(batchId, DEVICE_SOURCE,
                ImportResult.SUCCESS.getCode());
        if (phases.size() != SINGLE_ROW) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        int phase = phases.get(0);
        if (phase == AccountImportOnlinePhase.WAITING_LOGOUT) {
            if (details.releaseDeviceImport(batchId, AccountImportOnlinePhase.WAITING_LOGOUT,
                    AccountImportOnlinePhase.QUEUED) != SINGLE_ROW) {
                throw new BusinessException(ErrorCode.CONFLICT);
            }
        } else if (phase != AccountImportOnlinePhase.QUEUED && phase != AccountImportOnlinePhase.DISPATCHED
                && phase != AccountImportOnlinePhase.SETTLED) {
            throw new BusinessException(ErrorCode.CONFLICT);
        }
        return new DeviceImportVO(batchId, QUEUED);
    }

    private ParsedEntry validate(DeviceImportDTO request) {
        if (request == null || request.accountGroupId() == null || request.accountGroupId() <= 0
                || request.phone() == null || !PHONE.matcher(request.phone()).matches()
                || request.payload() == null || request.payload().isBlank()
                || LINE_BREAK.matcher(request.payload()).find()) {
            throw new BusinessException(ErrorCode.VALIDATION);
        }
        ParsedEntry entry = parser.parseDeviceParams(request.payload());
        if (entry.getParseError() != null || !request.phone().equals(entry.getWid())) {
            throw new BusinessException(ErrorCode.VALIDATION);
        }
        return entry;
    }
}
