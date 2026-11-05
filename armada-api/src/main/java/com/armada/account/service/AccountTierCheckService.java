package com.armada.account.service;
import com.armada.account.model.dto.AccountTierCheckRequestDTO;
import com.armada.account.model.dto.AccountTierCheckQuery;
import com.armada.account.model.vo.AccountTierCheckTaskVO;
import com.armada.account.model.vo.AccountTierCheckPreviewVO;
import com.armada.account.model.vo.AccountTierCheckItemVO;
import com.armada.account.model.vo.AccountTierCheckHistoryVO;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
/** 档位检测批次的应用入口，创建不触发协议查询。 */
public interface AccountTierCheckService {
    /** 预览范围/排除原因和冻结令牌。 */
    AccountTierCheckPreviewVO preview(AccountTierCheckRequestDTO req, AuthPrincipal principal);
    /** 幂等创建并冻结明细，后台显式启用后才查询。 */
    AccountTierCheckTaskVO create(AccountTierCheckRequestDTO req, AuthPrincipal principal);
    /** 当前可见批次 SQL 分页。 */
    PageResult<AccountTierCheckTaskVO> list(AccountTierCheckQuery q, AuthPrincipal principal);
    /** 读取当前可见批次的分布和统计。 */
    AccountTierCheckTaskVO detail(Long id, AuthPrincipal principal);
    /** 当前批次的明细 SQL 分页。 */
    PageResult<AccountTierCheckItemVO> items(Long id, AccountTierCheckQuery q, AuthPrincipal principal);
    /** 停止待查条目，已在途的只读结果可正常结算。 */
    void stop(Long id, AuthPrincipal principal);
    /** 只重试失败明细，不重查成功或不可查项。 */
    int retry(Long id, AuthPrincipal principal);
    /** 同一批账号创建新快照批次。 */
    AccountTierCheckTaskVO recheck(Long id, AuthPrincipal principal);
    /** 当前可见账号的倒序历史。 */
    AccountTierCheckHistoryVO history(Long accountId, AuthPrincipal principal);
}
