package com.armada.account.service;
import com.armada.account.model.dto.AccountMutualChatCreateDTO;
import com.armada.account.model.dto.AccountMutualChatQuery;
import com.armada.account.model.vo.AccountMutualChatItemVO;
import com.armada.account.model.vo.AccountMutualContactPreviewVO;
import com.armada.account.model.vo.AccountMutualChatTaskVO;
import com.armada.shared.response.PageResult;
import com.armada.shared.security.AuthPrincipal;
/** 账号列表独立互聊任务的应用服务。 */
public interface AccountMutualChatService {
    /** 预览当前可参与账号和双向操作规模。 */
    AccountMutualContactPreviewVO preview(AccountMutualChatCreateDTO request, AuthPrincipal principal);
    /** 幂等创建任务并冻结参与账号。 */
    AccountMutualChatTaskVO create(AccountMutualChatCreateDTO request, AuthPrincipal principal);
    /** 分页查询当前用户可见任务。 */
    PageResult<AccountMutualChatTaskVO> list(AccountMutualChatQuery query, AuthPrincipal principal);
    /** 读取任务及方向统计。 */
    AccountMutualChatTaskVO detail(Long id, AuthPrincipal principal);
    /** 分页查询定向发送事实。 */
    PageResult<AccountMutualChatItemVO> items(Long id, AccountMutualChatQuery query, AuthPrincipal principal);
    /** 停止未派发操作，保留在途回执接收。 */
    void stop(Long id, AuthPrincipal principal);
    /** 将失败、待确认和已取消方向重新排队；confirmUnknown 表示已确认重复发送风险。 */
    int retry(Long id, boolean confirmUnknown, AuthPrincipal principal);
}
