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
}
