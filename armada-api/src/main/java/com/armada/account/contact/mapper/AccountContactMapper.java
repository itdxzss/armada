package com.armada.account.contact.mapper;

import com.armada.account.contact.model.entity.AccountContact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 账号通讯录联系人快照的数据访问。 */
@Mapper
public interface AccountContactMapper {

    /**
     * 批量写入或更新本批联系人。
     *
     * @param rows 本批联系人行，必须非空
     * @return 受影响行数
     */
    int upsertBatch(@Param("rows") List<AccountContact> rows);

    /**
     * 删除本账号下早于本批同步时间的残留联系人。
     *
     * @param accountId 账号 ID
     * @param syncedAt 本批同步时间（epoch 毫秒）
     * @return 删除行数
     */
    int deleteStale(@Param("accountId") Long accountId, @Param("syncedAt") long syncedAt);

    /**
     * 统计本账号通讯录里有名字的联系人数。
     *
     * @param accountId 账号 ID
     * @return 有名字的联系人数
     */
    int countNamed(@Param("accountId") Long accountId);
}
