package com.armada.account.service.impl;

import com.armada.account.mapper.AccountRegistrationMapper;
import com.armada.account.model.dto.AccountImportDTO;
import com.armada.account.model.dto.AccountImportDetailQuery;
import com.armada.account.model.entity.AccountRegistrationItem;
import com.armada.account.model.entity.AccountRegistrationTask;
import com.armada.account.model.enums.AccountRegistrationState;
import com.armada.account.service.AccountImportService;
import com.armada.platform.registration.cobalt.model.CobaltSixCredential;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 六段落库与注册任务关联在同一短事务内，不执行HTTP。 */
@Service
public class AccountRegistrationImportService {
    /** 复用已有账号/凭据/导入队列。 */
    private final AccountImportService imports;
    /** 注册任务关联。 */
    private final AccountRegistrationMapper registrations;
    /** 装配导入与关联依赖。 */
    public AccountRegistrationImportService(AccountImportService imports, AccountRegistrationMapper registrations) {
        this.imports = imports;
        this.registrations = registrations;
    }
    /** 单次导入并关联，崩溃回滚不会留下没有注册明细关联的账号。 */
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public void importOne(AccountRegistrationTask task, AccountRegistrationItem item, CobaltSixCredential credential) {
        if (!Objects.equals(item.getPhoneNumber(), credential.phoneNumber())
                || !Objects.equals(item.getRegistrationId(), credential.registrationId())
                || !"zhuan-six-v1".equals(credential.format())) {
            throw new BusinessException(ErrorCode.VALIDATION, "注册凭据身份不匹配");
        }
        var metadata = new AccountImportDTO(task.getAccountGroupId(), 1, 1, task.getAccountType(),
                task.getIpRegion(), task.getIpAllocationMode(), "Cobalt注册导入", "registration-" + item.getId() + ".txt");
        var batch = imports.importAccounts(metadata, null, credential.sixLine());
        if (batch.totalRows() != 1 || batch.importedRows() != 1 || batch.duplicateRows() != 0 || batch.formatErrorRows() != 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "注册凭据未成功导入，请核对原账号");
        }
        AccountImportDetailQuery query = new AccountImportDetailQuery();
        query.setBatchId(batch.id());
        query.setPageSize(1);
        var details = imports.listDetails(query).list();
        if (details.size() != 1 || details.get(0).accountId() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "导入结果缺少账号关联");
        }
        item.setImportBatchId(batch.id());
        item.setAccountId(details.get(0).accountId());
        item.setState(AccountRegistrationState.WAITING_ONLINE.code());
        item.setUpdatedAt(System.currentTimeMillis());
        if (registrations.updateClaimed(item) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "注册执行租约已失效");
        }
    }
}
