package com.armada.account.mapper;

import com.armada.account.model.dto.SmsActivationQuery;
import com.armada.account.model.entity.SmsActivation;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 手动接码订单数据访问，全部操作由租户插件约束。 */
@Mapper
public interface SmsActivationMapper {
    /** 保存已取得的号码并回填主键。 */
    int insert(SmsActivation row);
    /** 当前租户的一条记录。 */
    SmsActivation find(Long id);
    /** 当前租户记录数。 */
    long count();
    /** 当前租户记录分页，按创建时间倒序。 */
    List<SmsActivation> list(SmsActivationQuery query);
    /**
     * 仅当仍处于原状态时迁移，避免并发轮询把终态改回。
     * @param row 携带 id、新状态、验证码、收码时间与变更时间
     * @param from 期望的原状态值
     * @return 更新行数
     */
    int transition(@Param("row") SmsActivation row, @Param("from") int from);
}
