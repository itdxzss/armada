package com.armada.resource.mapper;

import com.armada.resource.model.entity.GroupDataPackagePhone;
import com.armada.resource.model.dto.GroupDataPackagePhoneQuery;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 拉群数据包真实数据库访问。 */
@Mapper
public interface GroupDataPackagePhoneMapper {
    /** 分批写入导入号码 */
    int insertBatch(@Param("rows") List<GroupDataPackagePhone> rows);
    /** 包当前代已有号码 */
    List<GroupDataPackagePhone> existing(@Param("id") long id, @Param("generation") int generation, @Param("phones") List<String> phones);
    /** 号码分页总数 */
    long count(@Param("id") long id, @Param("generation") int generation, @Param("query") GroupDataPackagePhoneQuery query);
    /** 号码数据库分页 */
    List<GroupDataPackagePhone> page(@Param("id") long id, @Param("generation") int generation, @Param("query") GroupDataPackagePhoneQuery query);
    /** 有待确认/占用时禁止覆盖删除 */
    long activeCount(@Param("id") long id);
    /** 主要国家按当前代分布计算 */
    String primaryCountry(@Param("id") long id, @Param("generation") int generation);
    /** 读取最大序号 */
    int maxSeq(@Param("id") long id, @Param("generation") int generation);
    /** 升级未分配重复号码的管理员标记 */
    int promoteAdmin(@Param("ids") List<Long> ids);
    /** 当前租户的可靠历史隐私拒绝 */
    List<String> privacyHistory(@Param("phones") List<String> phones, @Param("cutoff") long cutoff);
    /** 当前代按状态导出 */
    List<GroupDataPackagePhone> export(@Param("id") long id, @Param("generation") int generation, @Param("statuses") List<Integer> statuses);
    /** 重置当前代明确可重试失败 */
    int resetFailed(@Param("id") long id, @Param("generation") int generation, @Param("now") long now);
    /** 读取未用快照 */
    List<GroupDataPackagePhone> snapshot(@Param("id") long id, @Param("generation") int generation, @Param("limit") int limit);
    /** 读取指定号码 */
    List<GroupDataPackagePhone> byIds(@Param("ids") List<Long> ids);
    /** 包锁后使用当前读，防止外层可重复读事务沿用领取前快照。 */
    List<GroupDataPackagePhone> lockByIds(@Param("ids") List<Long> ids);
    /** 分块原子领取，在包锁下校验后调用。 */
    int claimBatch(@Param("ids") List<Long> ids,
            @Param("request") com.armada.resource.service.GroupDataPackageAllocationService.ClaimRequest request,
            @Param("now") long now);
    /** 同一原状态和目标状态的条件批量结算。 */
    int transitionBatch(@Param("rows") List<GroupDataPackagePhone> rows, @Param("expected") int expected,
            @Param("target") int target, @Param("now") long now);
}
