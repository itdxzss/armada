package com.armada.account.service;

import com.armada.account.model.dto.AccountWsPhoneExportDTO;
import com.armada.account.model.vo.AccountWsPhoneExportFile;

/** 所选账号 WS 号码导出服务。 */
public interface AccountWsPhoneExportService {

    /**
     * 将前端所选未软删除账号的 WS 号码清洗、去重并组装为 UTF-8 TXT 文件，不限制账号状态。
     *
     * @param request 所选账号 ID 与可选分组名称
     * @return 文件名、文件字节及实际写入的唯一号码数量
     * @throws com.armada.shared.exception.BusinessException 请求无有效 ID、无可导出号码或导出失败
     */
    AccountWsPhoneExportFile export(AccountWsPhoneExportDTO request);
}
