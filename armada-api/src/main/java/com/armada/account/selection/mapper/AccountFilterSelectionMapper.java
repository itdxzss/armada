package com.armada.account.selection.mapper;

import com.armada.account.selection.AccountFilterCriteria;
import com.armada.account.selection.model.SelectedAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 按账号筛选条件圈号的数据访问。tenant_id 由租户拦截器注入。 */
@Mapper
public interface AccountFilterSelectionMapper {

    /**
     * 按筛选条件圈出可发送账号。
     *
     * @param criteria 圈选条件
     * @param normalAccountState 正常状态码，强制注入
     * @param exportedAccountState 已导出状态码，强制排除
     * @param limit 结果上限
     * @return 命中账号，按 priority 降序、id 升序
     */
    List<SelectedAccount> selectAccounts(
            @Param("criteria") AccountFilterCriteria criteria,
            @Param("normalAccountState") int normalAccountState,
            @Param("exportedAccountState") int exportedAccountState,
            @Param("limit") int limit);

    /**
     * 按同一套筛选条件统计命中账号数，用于任务抽屉的账号范围试算。
     *
     * <p>与 {@link #selectAccounts} 共用 XML 里的 {@code selectionWhere} 片段，
     * 保证「界面显示的命中数」与「真正会被圈到的号」永远是同一个口径。</p>
     *
     * @param criteria 圈选条件
     * @param normalAccountState 正常状态码，强制注入
     * @param exportedAccountState 已导出状态码，强制排除
     * @return 命中账号数
     */
    int countAccounts(
            @Param("criteria") AccountFilterCriteria criteria,
            @Param("normalAccountState") int normalAccountState,
            @Param("exportedAccountState") int exportedAccountState);

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
