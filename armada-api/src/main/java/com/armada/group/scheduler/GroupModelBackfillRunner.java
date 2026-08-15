package com.armada.group.scheduler;

import com.armada.group.mapper.GroupModelBackfillMapper;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
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
    private static final int BATCH_SIZE = 5_000;

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

    /** 仅在启动参数明确指定 run-once=true 时执行一次完整回填。 */
    @Override
    public void run(ApplicationArguments args) {
        BackfillResult result = backfillAll();
        log.info("群模型人工回填结束 batches={} affectedRows={}",
                result.batches(), result.affectedRows());
    }

    /**
     * 连续执行有界批次，直到没有可写数据；冲突发生时立即停止。
     *
     * @return 实际执行的非空批次数和数据库影响行数
     */
    public BackfillResult backfillAll() {
        validateSources();
        BackfillResult groups = backfillStage(() -> mapper.backfillGroups(BATCH_SIZE));
        BackfillResult profiles = backfillStage(() -> mapper.backfillProfiles(BATCH_SIZE));
        BackfillResult memberSnapshotHeaders = backfillStage(
                () -> mapper.backfillMemberSnapshotHeaders(BATCH_SIZE));
        BackfillResult invites = backfillStage(() -> mapper.backfillInvites(BATCH_SIZE));
        BackfillResult invitePointers = backfillStage(
                () -> mapper.backfillCurrentInvitePointers(BATCH_SIZE));
        BackfillResult profileOwners = backfillStage(
                () -> mapper.backfillProfileOwners(BATCH_SIZE));
        BackfillResult participants = backfillStage(
                () -> mapper.backfillParticipants(BATCH_SIZE));
        BackfillResult accountParticipants = backfillStage(
                () -> mapper.backfillAccountParticipants(BATCH_SIZE));
        BackfillResult joinFacts = backfillStage(
                () -> mapper.backfillParticipantJoinFacts(BATCH_SIZE));
        BackfillResult exitFacts = backfillStage(
                () -> mapper.backfillParticipantExitFacts(BATCH_SIZE));
        BackfillResult bindings = backfillStage(
                () -> mapper.backfillAccountGroupBindings(BATCH_SIZE));
        BackfillResult syncStates = backfillStage(
                () -> mapper.backfillAccountGroupSyncStates(BATCH_SIZE));
        validateSources();
        return new BackfillResult(
                groups.batches() + profiles.batches() + memberSnapshotHeaders.batches()
                        + invites.batches() + invitePointers.batches()
                        + profileOwners.batches() + participants.batches()
                        + accountParticipants.batches()
                        + joinFacts.batches() + exitFacts.batches()
                        + bindings.batches() + syncStates.batches(),
                groups.affectedRows() + profiles.affectedRows()
                        + memberSnapshotHeaders.affectedRows()
                        + invites.affectedRows() + invitePointers.affectedRows()
                        + profileOwners.affectedRows() + participants.affectedRows()
                        + accountParticipants.affectedRows()
                        + joinFacts.affectedRows() + exitFacts.affectedRows()
                        + bindings.affectedRows() + syncStates.affectedRows());
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
    private void validateSources() {
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
        int inviteConflicts = mapper.countInviteConflicts();
        if (inviteConflicts > 0) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "群模型回填发现邀请冲突，已停止写入: " + inviteConflicts);
        }
        int participantConflicts = mapper.countParticipantConflicts();
        if (participantConflicts > 0) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "群模型回填发现成员身份冲突，已停止写入: " + participantConflicts);
        }
        int bindingConflicts = mapper.countBindingConflicts();
        if (bindingConflicts > 0) {
            throw new BusinessException(
                    ErrorCode.CONFLICT,
                    "群模型回填发现账号关系或 baseline 冲突，已停止写入: " + bindingConflicts);
        }
    }

    /** 一次人工回填结果。 */
    public record BackfillResult(int batches, long affectedRows) {
    }
}
