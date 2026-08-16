package com.armada.group.scheduler;

import com.armada.group.mapper.GroupModelBackfillMapper;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;
import java.util.Locale;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/** 人工显式启动的新群模型一次性回填。 */
@Component
@ConditionalOnProperty(
        prefix = "armada.group-model-backfill",
        name = "run-once",
        havingValue = "true")
public class GroupModelBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GroupModelBackfillRunner.class);
    private static final int BATCH_SIZE = 50_000;
    private static final int LEGACY_MEMBER_SNAPSHOT_BATCH_SIZE = 5_000;
    private static final String START_STAGE_OPTION =
            "armada.group-model-backfill.start-stage";
    private static final String END_STAGE_OPTION =
            "armada.group-model-backfill.end-stage";

    private final GroupModelBackfillMapper mapper;
    private final TransactionOperations transactions;

    /**
     * 创建群模型回填入口。
     *
     * @param mapper 回填数据访问
     * @param transactionManager 事务管理器
     */
    @Autowired
    public GroupModelBackfillRunner(
            GroupModelBackfillMapper mapper,
            PlatformTransactionManager transactionManager) {
        this(mapper, new TransactionTemplate(transactionManager));
    }

    GroupModelBackfillRunner(
            GroupModelBackfillMapper mapper,
            TransactionOperations transactions) {
        this.mapper = mapper;
        this.transactions = transactions;
    }

    /** 仅在启动参数明确指定 run-once=true 时执行一次回填。 */
    @Override
    public void run(ApplicationArguments args) {
        BackfillStage startStage = resolveStage(args, START_STAGE_OPTION, BackfillStage.GROUPS);
        BackfillStage endStage = resolveStage(
                args, END_STAGE_OPTION, BackfillStage.ACCOUNT_GROUP_SYNC_STATES);
        if (startStage.ordinal() > endStage.ordinal()) {
            throw new IllegalArgumentException("群模型回填 start-stage 不能晚于 end-stage");
        }
        log.info("群模型人工回填开始 startStage={} endStage={}", startStage, endStage);
        BackfillResult result = backfillFrom(startStage, endStage);
        log.info("群模型人工回填结束 batches={} affectedRows={}",
                result.batches(), result.affectedRows());
    }

    /**
     * 从指定阶段连续执行有界批次；最后一批不足批次上限时直接结束，避免重复全表扫描。
     *
     * @return 实际执行的非空批次数和数据库影响行数
     */
    BackfillResult backfillFrom(BackfillStage startStage, BackfillStage endStage) {
        validateSources(startStage, endStage);
        int batches = 0;
        long affectedRows = 0;
        for (BackfillStep step : backfillSteps()) {
            if (step.stage().ordinal() < startStage.ordinal()) {
                continue;
            }
            if (step.stage().ordinal() > endStage.ordinal()) {
                break;
            }
            log.info("群模型人工回填阶段开始 stage={}", step.stage());
            BackfillResult stageResult = step.stage() == BackfillStage.LEGACY_MEMBER_SNAPSHOTS
                    ? backfillLegacyMemberSnapshots()
                    : backfillStage(step.writer());
            batches += stageResult.batches();
            affectedRows += stageResult.affectedRows();
            log.info("群模型人工回填阶段结束 stage={} batches={} affectedRows={}",
                    step.stage(), stageResult.batches(), stageResult.affectedRows());
        }
        validateSources(startStage, endStage);
        return new BackfillResult(batches, affectedRows);
    }

    private List<BackfillStep> backfillSteps() {
        return List.of(
                new BackfillStep(BackfillStage.GROUPS,
                        () -> mapper.backfillGroups(BATCH_SIZE)),
                new BackfillStep(BackfillStage.PROFILES,
                        () -> mapper.backfillProfiles(BATCH_SIZE)),
                new BackfillStep(BackfillStage.MEMBER_SNAPSHOT_HEADERS,
                        () -> mapper.backfillMemberSnapshotHeaders(BATCH_SIZE)),
                new BackfillStep(BackfillStage.INVITES,
                        () -> mapper.backfillInvites(BATCH_SIZE)),
                new BackfillStep(BackfillStage.CURRENT_INVITE_POINTERS,
                        () -> mapper.backfillCurrentInvitePointers(BATCH_SIZE)),
                new BackfillStep(BackfillStage.PROFILE_OWNERS,
                        () -> mapper.backfillProfileOwners(BATCH_SIZE)),
                new BackfillStep(BackfillStage.LEGACY_MEMBER_SNAPSHOTS,
                        () -> 0),
                new BackfillStep(BackfillStage.PARTICIPANTS,
                        () -> mapper.backfillParticipants(BATCH_SIZE)),
                new BackfillStep(BackfillStage.ACCOUNT_PARTICIPANTS,
                        () -> mapper.backfillAccountParticipants(BATCH_SIZE)),
                new BackfillStep(BackfillStage.PARTICIPANT_JOIN_FACTS,
                        () -> mapper.backfillParticipantJoinFacts(BATCH_SIZE)),
                new BackfillStep(BackfillStage.PARTICIPANT_EXIT_FACTS,
                        () -> mapper.backfillParticipantExitFacts(BATCH_SIZE)),
                new BackfillStep(BackfillStage.ACCOUNT_GROUP_BINDINGS,
                        () -> mapper.backfillAccountGroupBindings(BATCH_SIZE)),
                new BackfillStep(BackfillStage.ACCOUNT_GROUP_SYNC_STATES,
                        () -> mapper.backfillAccountGroupSyncStates(BATCH_SIZE)));
    }

    private BackfillResult backfillStage(IntSupplier writer) {
        int batches = 0;
        long affectedRows = 0;
        while (true) {
            int currentRows = backfillBatch(writer);
            if (currentRows == 0) {
                return new BackfillResult(batches, affectedRows);
            }
            batches++;
            affectedRows += currentRows;
            if (currentRows < BATCH_SIZE) {
                return new BackfillResult(batches, affectedRows);
            }
        }
    }

    /** 旧成员快照按源表主键只扫描一次；每个主键区间独立提交。 */
    private BackfillResult backfillLegacyMemberSnapshots() {
        long afterId = 0;
        int batches = 0;
        long affectedRows = 0;
        while (true) {
            long batchStartId = afterId;
            LegacyMemberSnapshotBatch batch = transactions.execute(status -> {
                Long endId = mapper.selectLegacyMemberSnapshotBatchEndId(
                        batchStartId, LEGACY_MEMBER_SNAPSHOT_BATCH_SIZE);
                if (endId == null) {
                    return null;
                }
                int currentRows = mapper.backfillLegacyMemberSnapshots(batchStartId, endId);
                return new LegacyMemberSnapshotBatch(endId, currentRows);
            });
            if (batch == null) {
                return new BackfillResult(batches, affectedRows);
            }
            afterId = batch.endId();
            batches++;
            affectedRows += batch.affectedRows();
            log.info(
                    "群模型人工回填批次完成 stage={} batch={} endId={} affectedRows={}",
                    BackfillStage.LEGACY_MEMBER_SNAPSHOTS,
                    batches,
                    batch.endId(),
                    batch.affectedRows());
        }
    }

    private BackfillStage resolveStage(
            ApplicationArguments args,
            String option,
            BackfillStage defaultStage) {
        List<String> values = args.getOptionValues(option);
        if (values == null || values.isEmpty()) {
            return defaultStage;
        }
        if (values.size() != 1 || values.get(0).isBlank()) {
            throw new IllegalArgumentException("群模型回填阶段参数必须且只能指定一个值: " + option);
        }
        String normalized = values.get(0).trim()
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
        try {
            return BackfillStage.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "未知群模型回填阶段: " + values.get(0), exception);
        }
    }

    private int backfillBatch(IntSupplier writer) {
        Integer affectedRows = transactions.execute(
                status -> backfillBatchInTransaction(writer));
        if (affectedRows == null) {
            throw new IllegalStateException("群模型回填事务未返回影响行数");
        }
        return affectedRows;
    }

    private int backfillBatchInTransaction(IntSupplier writer) {
        return writer.getAsInt();
    }

    /** 回填前后各校验一次来源，避免每个批次重复扫描全部旧表。 */
    private void validateSources(BackfillStage startStage, BackfillStage endStage) {
        int invalidSources = mapper.countInvalidGroupSources();
        if (invalidSources > 0) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "群模型回填发现非法群来源，已停止写入: " + invalidSources);
        }
        int conflicts = mapper.countDuplicateGroupJids();
        if (conflicts > 0) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "群模型回填发现租户内重复群 JID，已停止写入: " + conflicts);
        }
        if (includes(startStage, endStage, BackfillStage.INVITES)
                || includes(startStage, endStage, BackfillStage.CURRENT_INVITE_POINTERS)) {
            int inviteConflicts = mapper.countInviteConflicts();
            if (inviteConflicts > 0) {
                throw new BusinessException(
                        ErrorCode.CONFLICT,
                        "群模型回填发现邀请冲突，已停止写入: " + inviteConflicts);
            }
        }
        if (endStage.ordinal() >= BackfillStage.PROFILE_OWNERS.ordinal()
                && startStage.ordinal() <= BackfillStage.PARTICIPANT_EXIT_FACTS.ordinal()) {
            int participantConflicts = mapper.countParticipantConflicts();
            if (participantConflicts > 0) {
                throw new BusinessException(
                        ErrorCode.CONFLICT,
                        "群模型回填发现成员身份冲突，已停止写入: " + participantConflicts);
            }
        }
        if (endStage.ordinal() >= BackfillStage.ACCOUNT_PARTICIPANTS.ordinal()) {
            int bindingConflicts = mapper.countBindingConflicts();
            if (bindingConflicts > 0) {
                throw new BusinessException(
                        ErrorCode.CONFLICT,
                        "群模型回填发现账号关系或 baseline 冲突，已停止写入: "
                                + bindingConflicts);
            }
        }
    }

    private static boolean includes(
            BackfillStage startStage,
            BackfillStage endStage,
            BackfillStage candidate) {
        return candidate.ordinal() >= startStage.ordinal()
                && candidate.ordinal() <= endStage.ordinal();
    }

    /** 一次人工回填结果。 */
    public record BackfillResult(int batches, long affectedRows) {
    }

    /** 人工回填允许续跑的固定阶段，声明顺序就是执行顺序。 */
    enum BackfillStage {
        /** 群身份。 */
        GROUPS,
        /** 群资料。 */
        PROFILES,
        /** 成员快照头。 */
        MEMBER_SNAPSHOT_HEADERS,
        /** 邀请码。 */
        INVITES,
        /** 当前邀请码指针。 */
        CURRENT_INVITE_POINTERS,
        /** 群主成员。 */
        PROFILE_OWNERS,
        /** 旧列表完整成员快照。 */
        LEGACY_MEMBER_SNAPSHOTS,
        /** 普通成员当前态。 */
        PARTICIPANTS,
        /** 账号自身成员态。 */
        ACCOUNT_PARTICIPANTS,
        /** 最近进群事实。 */
        PARTICIPANT_JOIN_FACTS,
        /** 最近退群事实。 */
        PARTICIPANT_EXIT_FACTS,
        /** 账号群关系。 */
        ACCOUNT_GROUP_BINDINGS,
        /** 账号群同步状态。 */
        ACCOUNT_GROUP_SYNC_STATES
    }

    private record BackfillStep(BackfillStage stage, IntSupplier writer) {
    }

    private record LegacyMemberSnapshotBatch(long endId, int affectedRows) {
    }
}
