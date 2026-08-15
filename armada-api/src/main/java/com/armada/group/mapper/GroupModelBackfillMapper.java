package com.armada.group.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 旧群当前事实向新模型分批回填的数据访问。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface GroupModelBackfillMapper {

    /**
     * 统计 JID 非法或找不到同租户旧群入口的预览来源。
     *
     * @return 非法来源行数
     */
    int countInvalidGroupSources();

    /**
     * 统计租户内映射到同一规范化群 JID 的多条旧入口。
     *
     * @return 冲突的租户群数量
     */
    int countDuplicateGroupJids();

    /**
     * 统计重复邀请码及邀请码已绑定到不同群的冲突。
     *
     * @return 邀请冲突数量
     */
    int countInviteConflicts();

    /** 统计旧成员事实中无法解析的群或成员身份。 */
    int countParticipantConflicts();

    /** 统计账号关系和 baseline 中无法保守迁移的证据冲突。 */
    int countBindingConflicts();

    /**
     * 按租户和群 JID 顺序回填一批群身份及本地字段。
     *
     * @param limit 单批最大群数
     * @return 实际插入或更新行数
     */
    int backfillGroups(@Param("limit") int limit);

    /** 按群主键顺序回填一批群资料。 */
    int backfillProfiles(@Param("limit") int limit);

    /** 从已完成的旧成员缓存回填一批完整成员快照头。 */
    int backfillMemberSnapshotHeaders(@Param("limit") int limit);

    /** 按租户和邀请码顺序回填一批邀请。 */
    int backfillInvites(@Param("limit") int limit);

    /** 按群主键顺序回填一批当前邀请指针。 */
    int backfillCurrentInvitePointers(@Param("limit") int limit);

    /** 从旧成员当前态按群和成员身份顺序回填一批成员。 */
    int backfillParticipants(@Param("limit") int limit);

    /** 从账号关系和合法 baseline 回填一批账号自身成员。 */
    int backfillAccountParticipants(@Param("limit") int limit);

    /** 回填一批成员最近可靠进群事实。 */
    int backfillParticipantJoinFacts(@Param("limit") int limit);

    /** 回填一批成员最近可靠退群事实。 */
    int backfillParticipantExitFacts(@Param("limit") int limit);

    /** 回填一批账号与群关系；legacy 不生成上控后首次观察时间。 */
    int backfillAccountGroupBindings(@Param("limit") int limit);

    /** 回填一批账号群 baseline 和同步水位。 */
    int backfillAccountGroupSyncStates(@Param("limit") int limit);
}
