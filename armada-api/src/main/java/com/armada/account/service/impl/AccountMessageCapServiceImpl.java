package com.armada.account.service.impl;

import com.armada.account.converter.AccountMessageCapConverter;
import com.armada.account.model.dto.AccountMessageCapQueryDTO;
import com.armada.account.model.enums.AccountMessageCapQueryStatus;
import com.armada.account.model.vo.AccountMessageCapBatchVO;
import com.armada.account.model.vo.AccountMessageCapItemVO;
import com.armada.account.model.vo.AccountMessageCapVO;
import com.armada.account.service.AccountMessageCapService;
import com.armada.account.service.AccountProtocolLookupService;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.ProtocolMessageCap;
import com.armada.platform.protocol.port.AccountMessageCapPort;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 账号新聊天额度批量查询实现。
 *
 * <p>账号寻址在请求线程内完成（租户隔离、软删除与协议身份校验），之后只做协议调用，
 * 不访问数据库；用固定大小的共享线程池限制同时打到协议层的请求数，避免一次大批量压垮协议层。</p>
 */
@Service
public class AccountMessageCapServiceImpl implements AccountMessageCapService {

    private static final Logger log = LoggerFactory.getLogger(AccountMessageCapServiceImpl.class);

    /** 单次请求最多查询的账号数。 */
    static final int MAX_ACCOUNTS = 100;
    /** 同时向协议层发起额度查询的最大并发。 */
    private static final int PARALLELISM = 8;
    private static final String THREAD_NAME = "account-message-cap";

    private final AccountProtocolLookupService lookupService;
    private final AccountMessageCapPort port;
    private final AccountMessageCapConverter converter;
    private final ExecutorService executor = Executors.newFixedThreadPool(PARALLELISM, runnable -> {
        Thread thread = new Thread(runnable, THREAD_NAME);
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 创建额度批量查询服务。
     *
     * @param lookupService 账号域协议寻址服务
     * @param port 新聊天额度协议端口
     * @param converter 协议结果到 VO 的转换器
     */
    public AccountMessageCapServiceImpl(
            AccountProtocolLookupService lookupService,
            AccountMessageCapPort port,
            AccountMessageCapConverter converter) {
        this.lookupService = lookupService;
        this.port = port;
        this.converter = converter;
    }

    /** 应用关闭时停止线程池，避免遗留查询线程。 */
    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @Override
    public AccountMessageCapBatchVO query(AccountMessageCapQueryDTO request) {
        List<Long> ids = normalizeIds(request == null ? null : request.ids());
        boolean debug = request.debug();
        Map<Long, ProtocolAccountRef> refs = lookupService.findActiveProtocolRefs(ids).stream()
                .collect(Collectors.toMap(ProtocolAccountRef::armadaAccountId, Function.identity(),
                        (first, second) -> first, LinkedHashMap::new));
        List<CompletableFuture<AccountMessageCapItemVO>> futures = ids.stream()
                .map(id -> refs.containsKey(id)
                        ? CompletableFuture.supplyAsync(() -> queryOne(refs.get(id), debug), executor)
                        : CompletableFuture.completedFuture(unavailable(id)))
                .toList();
        // queryOne 已把所有异常收敛为单账号结果，join 不会因某个账号失败而整体失败。
        List<AccountMessageCapItemVO> items = futures.stream().map(CompletableFuture::join).toList();
        int succeeded = (int) items.stream()
                .filter(item -> item.queryStatus() == AccountMessageCapQueryStatus.SUCCESS).count();
        return new AccountMessageCapBatchVO(items.size(), succeeded, items.size() - succeeded, items);
    }

    private AccountMessageCapItemVO queryOne(ProtocolAccountRef ref, boolean debug) {
        try {
            ProtocolMessageCap cap = port.query(ref, debug);
            AccountMessageCapQueryStatus status = ProtocolMessageCap.REJECTED.equals(cap.resultStatus())
                    ? AccountMessageCapQueryStatus.REJECTED
                    : AccountMessageCapQueryStatus.SUCCESS;
            return item(ref, status, null, converter.toVO(cap));
        } catch (ProtocolException ex) {
            AccountMessageCapQueryStatus status = classify(ex);
            if (status == AccountMessageCapQueryStatus.FAILED) {
                log.warn("账号额度查询失败 accountId={} errorCode={}", ref.armadaAccountId(), ex.errorCode());
            }
            return item(ref, status,
                    status == AccountMessageCapQueryStatus.FAILED ? ex.errorCode().name() : null, null);
        } catch (RuntimeException ex) {
            // 防御：任何未预期异常也只影响当前账号，且不把异常文本带给前端。
            log.error("账号额度查询异常 accountId={}", ref.armadaAccountId(), ex);
            return item(ref, AccountMessageCapQueryStatus.FAILED, ex.getClass().getSimpleName(), null);
        }
    }

    private static AccountMessageCapQueryStatus classify(ProtocolException ex) {
        return switch (ex.errorCode()) {
            case ACCOUNT_NOT_ONLINE, ACCOUNT_NOT_FOUND -> AccountMessageCapQueryStatus.OFFLINE;
            case UNSUPPORTED_BACKEND -> AccountMessageCapQueryStatus.UNSUPPORTED_BACKEND;
            default -> AccountMessageCapQueryStatus.FAILED;
        };
    }

    private static AccountMessageCapItemVO item(ProtocolAccountRef ref, AccountMessageCapQueryStatus status,
            String errorCode, AccountMessageCapVO cap) {
        return new AccountMessageCapItemVO(
                ref.armadaAccountId(), ref.wsPhone(), ref.backend().name(), status, errorCode, cap);
    }

    private static AccountMessageCapItemVO unavailable(Long accountId) {
        return new AccountMessageCapItemVO(
                accountId, null, null, AccountMessageCapQueryStatus.ACCOUNT_UNAVAILABLE, null, null);
    }

    private static List<Long> normalizeIds(List<Long> ids) {
        List<Long> distinct = ids == null ? List.of()
                : ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "请选择要查询的账号");
        }
        if (distinct.size() > MAX_ACCOUNTS) {
            throw new BusinessException(ErrorCode.VALIDATION, "单次最多查询 " + MAX_ACCOUNTS + " 个账号");
        }
        return distinct;
    }
}
