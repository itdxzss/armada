package com.armada.account.service;

import com.armada.account.model.dto.AccountImportDTO;
import com.armada.account.model.dto.AccountImportDetailQuery;
import com.armada.account.model.dto.AccountImportQuery;
import com.armada.account.model.vo.AccountImportBatchListVO;
import com.armada.account.model.vo.AccountImportBatchVO;
import com.armada.account.model.vo.AccountImportDetailVO;
import com.armada.account.model.vo.AccountImportExportFile;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;

/**
 * 账号导入业务接口。
 *
 * <p>负责解析文件/文本内容、逐行三步原子写(account + account_state + account_credential),
 * 并汇总导入计数写入批次表,返回批次 VO。提供批次/明细分页读取及原始格式导出。</p>
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

    /**
     * 分页查询导入批次列表(LEFT JOIN account_group 取组名)。
     *
     * <p>SQL 下推分页,结果按创建时间倒序排列。所有筛选条件均可选,默认返回当前租户全部批次。</p>
     *
     * @param query 批次列表查询参数(含分页/筛选字段)
     * @return 分页结果,元素为 AccountImportBatchListVO(record VO,含 groupName)
     */
    PageResult<AccountImportBatchListVO> listBatches(AccountImportQuery query);

    /**
     * 分页查询指定批次的导入明细列表。
     *
     * <p>filter=all/success/fail 三种范围;SQL 下推分页,结果按行号升序排列。</p>
     *
     * @param query 明细查询参数(batchId 必传,filter 可选默认 all)
     * @return 分页结果,元素为 AccountImportDetailVO(含 parseResultLabel、groupName)
     * @throws BusinessException batchId 为 null 时抛出
     */
    PageResult<AccountImportDetailVO> listDetails(AccountImportDetailQuery query);

    /**
     * 导出指定批次的明细为导入时的原始容器格式。
     *
     * <p>ZIP 导入导出 ZIP,TXT/粘贴导入导出 TXT。scope=all/success/fail;
     * 无匹配行时返回对应类型的空文件。</p>
     *
     * @param batchId 批次 ID(必传)
     * @param scope   结果范围:all=全部;success=只成功;fail=只失败
     * @return 文件响应对象(文件名/content-type/字节)
     * @throws BusinessException batchId 为空或批次缺少原始导出材料时抛出
     */
    AccountImportExportFile exportDetails(Long batchId, String scope);
}
