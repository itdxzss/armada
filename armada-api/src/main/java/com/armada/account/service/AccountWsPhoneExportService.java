package com.armada.account.service;

import com.armada.account.model.dto.AccountWsPhoneExportDTO;
import com.armada.account.model.vo.AccountWsPhoneExportFile;

/** 所选账号 WS 号码导出服务。 */
public interface AccountWsPhoneExportService {
    AccountWsPhoneExportFile export(AccountWsPhoneExportDTO request);
}
