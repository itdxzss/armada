package com.armada.account.contact.mapper;

import com.armada.account.contact.model.entity.AccountContactSync;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 账号通讯录同步状态的数据访问。 */
@Mapper
public interface AccountContactSyncMapper {

    /**
     * 读取账号当前同步状态。
     *
     * @param accountId 账号 ID
     * @return 同步状态行，从未同步过时为 null
     */
    AccountContactSync selectByAccountId(@Param("accountId") Long accountId);

    /**
     * 写入或更新账号同步状态。
     *
     * @param row 同步状态行
     * @return 受影响行数
     */
    int upsert(AccountContactSync row);
}
