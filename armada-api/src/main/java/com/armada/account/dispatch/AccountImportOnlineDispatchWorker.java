package com.armada.account.dispatch;

import com.armada.account.mapper.AccountImportDetailMapper;
import com.armada.account.mapper.AccountImportBatchMapper;
import com.armada.account.model.entity.AccountImportBatch;
import com.armada.account.model.entity.AccountImportDetail;
import com.armada.account.model.entity.AccountImportOnlinePhase;
import com.armada.account.model.entity.ImportResult;
import com.armada.account.model.vo.AccountBatchOnlineVO;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.security.DataScope;
import com.armada.shared.security.DataScopeContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单租户账号导入自动上线派发 worker。
 *
 * <p>在一个事务中锁定最多 500 条 QUEUED 明细,复用现有批量上线服务写协议 outbox。
 * 该 worker 的每轮派发量低于账号批量命令 1000 上限,用于控制导入自动上线节奏。
 * 成功后把这些明细推进到 DISPATCHED。若批量上线链路抛异常,事务整体回滚,明细保持 QUEUED 等待重试。</p>
 */
@Service
public class AccountImportOnlineDispatchWorker {

    private static final Logger log = LoggerFactory.getLogger(AccountImportOnlineDispatchWorker.class);

    /** 导入自动上线每轮派发量。 */
    private static final int BATCH_SIZE = 500;

    /** 待派发阶段码。 */
    static final int QUEUED_PHASE = AccountImportOnlinePhase.QUEUED;

    private final AccountImportDetailMapper detailMapper;
    private final AccountImportBatchMapper batchMapper;
    private final AccountOnlineCommandService onlineCommandService;

    /**
     * 创建单租户自动上线派发 worker。
     *
     * @param detailMapper         导入明细 mapper
     * @param batchMapper          导入批次 mapper
     * @param onlineCommandService 账号批量上线命令服务
     */
    public AccountImportOnlineDispatchWorker(AccountImportDetailMapper detailMapper,
                                             AccountImportBatchMapper batchMapper,
                                             AccountOnlineCommandService onlineCommandService) {
        this.detailMapper = detailMapper;
        this.batchMapper = batchMapper;
        this.onlineCommandService = onlineCommandService;
    }

    /**
     * 派发当前租户的一批待上线导入明细。
     *
     * @param tenantId 当前租户 ID,用于锁行 SQL 显式过滤
     * @return 推进到 DISPATCHED 的明细行数
     */
    @Transactional(rollbackFor = Exception.class)
    public int dispatchTenantBatch(Long tenantId) {
        List<AccountImportDetail> details = detailMapper.selectQueuedForUpdate(
                tenantId,
                QUEUED_PHASE,
                ImportResult.SUCCESS.getCode(),
                BATCH_SIZE);
        if (details.isEmpty()) {
            return 0;
        }

        Long batchId = details.get(0).getBatchId();
        if (batchId == null || details.stream().anyMatch(detail -> !batchId.equals(detail.getBatchId()))) {
            throw new BusinessException(ErrorCode.CONFLICT, "导入自动上线单批次锁定结果不一致");
        }
        AccountImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null || batch.getOwnerUserId() == null || batch.getOwnerUserId() <= 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "导入自动上线批次缺少可信 owner");
        }

        try (DataScopeContext.Scope ignored =
                     DataScopeContext.open(DataScope.self(batch.getOwnerUserId()))) {
            List<Long> detailIds = details.stream()
                    .map(AccountImportDetail::getId)
                    .toList();
            List<Long> accountIds = details.stream()
                    .map(AccountImportDetail::getAccountId)
                    .toList();

            AccountBatchOnlineVO result = onlineCommandService.onlineBatch(accountIds);
            if (result.accepted() != accountIds.size()) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "导入自动上线 outbox 受理数量不一致: expected=" + accountIds.size()
                                + ", accepted=" + result.accepted());
            }

            long dispatchedAt = System.currentTimeMillis();
            int updated = detailMapper.markDispatched(
                    detailIds,
                    QUEUED_PHASE,
                    AccountImportOnlinePhase.DISPATCHED,
                    dispatchedAt);
            if (updated != detailIds.size()) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "导入自动上线明细推进数量不一致: expected=" + detailIds.size()
                                + ", updated=" + updated);
            }
            log.info("账号导入自动上线单租户派发完成 tenantId={} ownerUserId={} count={} dispatchedAt={}",
                    tenantId, batch.getOwnerUserId(), updated, dispatchedAt);
            return updated;
        }
    }
}
