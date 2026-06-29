package com.armada.account.dispatch;

import com.armada.account.mapper.AccountImportDetailMapper;
import com.armada.account.model.entity.AccountImportDetail;
import com.armada.account.model.entity.AccountImportOnlinePhase;
import com.armada.account.model.entity.ImportResult;
import com.armada.account.model.vo.AccountBatchOnlineVO;
import com.armada.account.service.AccountOnlineCommandService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单租户账号导入自动上线派发 worker。
 *
 * <p>在一个事务中锁定最多 500 条 QUEUED 明细,复用现有批量上线服务写协议 outbox,
 * 成功后把这些明细推进到 DISPATCHED。若批量上线链路抛异常,事务整体回滚,明细保持 QUEUED 等待重试。</p>
 */
@Service
public class AccountImportOnlineDispatchWorker {

    private static final Logger log = LoggerFactory.getLogger(AccountImportOnlineDispatchWorker.class);

    /** 与账号批量上线和协议 outbox 单批上限保持一致。 */
    private static final int BATCH_SIZE = 500;

    /** 待派发阶段码。 */
    static final int QUEUED_PHASE = AccountImportOnlinePhase.QUEUED;

    private final AccountImportDetailMapper detailMapper;
    private final AccountOnlineCommandService onlineCommandService;

    /**
     * 创建单租户自动上线派发 worker。
     *
     * @param detailMapper         导入明细 mapper
     * @param onlineCommandService 账号批量上线命令服务
     */
    public AccountImportOnlineDispatchWorker(AccountImportDetailMapper detailMapper,
                                             AccountOnlineCommandService onlineCommandService) {
        this.detailMapper = detailMapper;
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
                    "导入自动上线明细推进数量不一致: expected=" + detailIds.size() + ", updated=" + updated);
        }
        log.info("账号导入自动上线单租户派发完成 tenantId={} count={} dispatchedAt={}",
                tenantId, updated, dispatchedAt);
        return updated;
    }
}
