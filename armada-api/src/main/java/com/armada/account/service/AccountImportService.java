package com.armada.account.service;

import com.armada.account.model.dto.AccountImportDTO;
import com.armada.account.model.vo.AccountImportBatchVO;
import com.armada.shared.exception.BusinessException;

/**
 * 账号导入业务接口。
 *
 * <p>负责解析文件/文本内容、逐行三步原子写(account + account_state + account_credential),
 * 并汇总导入计数写入批次表,返回批次 VO。</p>
 */
public interface AccountImportService {

    /**
     * 导入账号:解析 → 逐行原子写 → 写批次/明细 → 返回批次 VO。
     *
     * <p>fileBytes 与 text 二选一(text 非空时优先)。成功行写三张表(account/account_state/account_credential),
     * 失败行仅写明细不建号。整批无论成败均以 status=2(已完成)结束;成败通过计数列表达。</p>
     *
     * @param meta      导入元信息(目标分组/格式/机型/账号类型/IP 地区/批次名)
     * @param fileBytes 上传文件字节(可为 null)
     * @param text      文本内容(可为 null;非空时优先于 fileBytes)
     * @return 导入批次 VO(含总数/成功/重复/格式错误计数及批次 ID)
     * @throws BusinessException 导入内容为空(无可导入条目)或格式枚举非法时抛出
     */
    AccountImportBatchVO importAccounts(AccountImportDTO meta, byte[] fileBytes, String text);
}
