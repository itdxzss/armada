package com.armada.account.mapper;

import com.armada.account.model.entity.AccountImportDetail;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 账号导入明细数据访问。tenant_id 由租户行隔离拦截器自动注入。
 */
@Mapper
public interface AccountImportDetailMapper {

    /**
     * 批量插入明细行(&lt;foreach&gt; 多值 INSERT)。
     * tenant_id 由拦截器注入,不手写。
     */
    int batchInsert(@Param("rows") List<AccountImportDetail> rows);
}
