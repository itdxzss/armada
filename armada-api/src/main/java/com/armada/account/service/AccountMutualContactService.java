package com.armada.account.service;
import com.armada.account.model.dto.AccountMutualContactCreateDTO;
import com.armada.account.model.dto.AccountMutualContactQuery;
import com.armada.account.model.vo.AccountMutualContactItemVO;
import com.armada.account.model.vo.AccountMutualContactPreviewVO;
import com.armada.account.model.vo.AccountMutualContactTaskVO;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
/** 账号列表独立互存任务的应用服务。 */
public interface AccountMutualContactService {
    /** 预览当前可参与账号和双向操作规模。 */
    AccountMutualContactPreviewVO preview(AccountMutualContactCreateDTO request, AuthPrincipal principal);
    /** 幂等创建任务并冻结参与账号。 */
    AccountMutualContactTaskVO create(AccountMutualContactCreateDTO request, AuthPrincipal principal);
    /** 分页查询当前用户可见任务。 */
    PageResult<AccountMutualContactTaskVO> list(AccountMutualContactQuery query, AuthPrincipal principal);
    /** 读取任务及方向统计。 */
    AccountMutualContactTaskVO detail(Long id, AuthPrincipal principal);
    /** 分页查询定向保存事实。 */
    PageResult<AccountMutualContactItemVO> items(Long id, AccountMutualContactQuery query, AuthPrincipal principal);
    /** 停止未派发操作，保留在途回执接收。 */
    void stop(Long id, AuthPrincipal principal);
    /** 仅将明确可重试失败项重新排队。 */
    int retry(Long id, AuthPrincipal principal);
}
