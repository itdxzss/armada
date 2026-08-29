package com.armada.account.selection.mapper;

import com.armada.account.selection.model.SelectedAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 发送前的账号协议事实复查。tenant_id 由租户拦截器注入。
 *
 * <p>账号圈选统一走 {@code AccountHyperlinkCandidateService}，两个菜单共用一份 WHERE；
 * 这里只保留「圈号之后、真正发送之前」的复查，条件固定不接受外部筛选。</p>
 */
@Mapper
public interface AccountFilterSelectionMapper {

    /** 账号状态:正常。 */
    int ACCOUNT_STATE_NORMAL = 2;

    /** 账号状态:已导出。 */
    int ACCOUNT_STATE_EXPORTED = 4;



    /**
     * 按账号 ID 批量复查协议事实。轮次执行时用来确认圈号后账号仍可发送。
     *
     * @param accountIds 账号 ID，<b>调用方必须保证非空</b>
     * @param normalAccountState 正常状态码
     * @param exportedAccountState 已导出状态码
     * @return 仍可发送的账号；被封或已导出的不会出现在结果里
     */
    List<SelectedAccount> selectSendableByIds(
            @Param("accountIds") List<Long> accountIds,
            @Param("normalAccountState") int normalAccountState,
            @Param("exportedAccountState") int exportedAccountState);
}
