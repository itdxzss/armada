package com.armada.account.service.impl;

import com.armada.account.converter.AccountConverter;
import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.dto.AccountQuery;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountDeleteGateRow;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.enums.AccountMarketingOccupancyType;
import com.armada.account.model.vo.AccountListVO;
import com.armada.account.model.vo.AccountListVoRow;
import com.armada.account.model.vo.AccountMarketingOccupancyTaskRow;
import com.armada.account.model.vo.AccountStatsVO;
import com.armada.account.model.vo.AccountStatsVoRow;
import com.armada.account.service.AccountService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账号列表菜单业务实现。
 *
 * <p>负责账号分页、统计、登录态读取、人工迁移和批量删除。账号分页默认只读取
 * 账号及分组锁字段；只有使用营销占用高级筛选时才额外解析匹配的分组，避免营销业务表
 * 进入默认分页主查询。</p>
 *
 * <p>租户隔离由 MyBatis 租户拦截器统一处理，本类不接收或拼接 {@code tenant_id}；
 * 时间字段统一由业务层写入 epoch 毫秒。</p>
 */
@Service
public class AccountServiceImpl implements AccountService {

    /** 账号列表业务日志。 */
    private static final Logger log = LoggerFactory.getLogger(AccountServiceImpl.class);

    /**
     * 可删除账号状态集合:封禁 / 导出 / 解绑。
     * 对应 account_state 表 account_state 列口径,使用 {@link AccountStateCode} 常量替代魔法值。
     */
    private static final Set<Integer> DELETABLE_STATES = Set.of(
            AccountStateCode.BANNED, AccountStateCode.EXPORTED, AccountStateCode.UNBOUND);

    /** 账号、账号状态及账号分页数据访问。 */
    private final AccountMapper accountMapper;

    /** 账号分组及分组营销占用数据访问。 */
    private final AccountGroupMapper accountGroupMapper;

    /** 账号分页查询投影转换器。 */
    private final AccountConverter accountConverter;

    /**
     * 创建账号列表业务服务。
     *
     * @param accountMapper 账号、账号状态及账号分页数据访问
     * @param accountGroupMapper 账号分组及分组营销占用数据访问
     * @param accountConverter 账号分页查询投影转换器
     */
    public AccountServiceImpl(AccountMapper accountMapper,
                              AccountGroupMapper accountGroupMapper,
                              AccountConverter accountConverter) {
        this.accountMapper = accountMapper;
        this.accountGroupMapper = accountGroupMapper;
        this.accountConverter = accountConverter;
    }

    /**
     * 分页查询当前租户的账号列表。
     *
     * <p>营销占用高级条件先解析为分组 ID，再由账号分页 SQL 过滤；默认查询不关联营销任务表。
     * 当前页仅按去重后的占用任务 ID 批量补充状态，用于派生暂停占用和待释放标签。</p>
     *
     * @param query 账号筛选、营销占用筛选及分页参数
     * @return 当前页账号及总数
     */
    @Override
    public PageResult<AccountListVO> listAccounts(AccountQuery query) {
        query.setResolvedOccupancyGroupIds(null);
        if (hasAdvancedOccupancyFilter(query)) {
            List<Long> groupIds = accountGroupMapper.selectMarketingOccupancyGroupIds(query);
            if (groupIds.isEmpty()) {
                log.debug("账号营销占用高级筛选未命中分组 type={} taskKeyword={} businessType={}",
                        query.getMarketingOccupancyType(),
                        query.getOccupiedTaskKeyword(),
                        query.getOccupiedBusinessType());
                return PageResult.of(Collections.emptyList(), query.getPage(), query.getPageSize(), 0);
            }
            query.setResolvedOccupancyGroupIds(groupIds);
            log.debug("账号营销占用高级筛选已解析分组 count={}", groupIds.size());
        }
        long total = accountMapper.countPage(query);
        if (total == 0) {
            return PageResult.of(Collections.emptyList(), query.getPage(), query.getPageSize(), 0);
        }
        List<AccountListVoRow> rows = accountMapper.selectPage(query);
        Map<Long, AccountMarketingOccupancyTaskRow> tasksById = occupancyTasksById(rows);
        List<AccountListVO> list = rows.stream()
                .map(row -> accountConverter.toAccountListVO(row, resolveOccupancyType(row, tasksById)))
                .toList();
        return PageResult.of(list, query.getPage(), query.getPageSize(), total);
    }

    /**
     * 判断是否需要在账号分页前解析营销占用分组。
     *
     * @param query 账号列表查询参数
     * @return 存在占用类型、任务关键词或任务业务类型筛选时返回 {@code true}
     */
    private boolean hasAdvancedOccupancyFilter(AccountQuery query) {
        return hasText(query.getMarketingOccupancyType())
                || hasText(query.getOccupiedTaskKeyword())
                || query.getOccupiedBusinessType() != null;
    }

    /**
     * 判断文本是否包含非空白内容。
     *
     * @param value 待检查文本
     * @return 文本非空且不全为空白时返回 {@code true}
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 按当前页去重后的营销任务 ID 一次读取状态，避免账号列表形成 N+1 查询。
     *
     * @param rows 当前页账号查询投影
     * @return 以营销任务 ID 为键的任务状态投影；当前页无占用任务时返回空 Map
     */
    private Map<Long, AccountMarketingOccupancyTaskRow> occupancyTasksById(List<AccountListVoRow> rows) {
        Set<Long> taskIds = rows.stream()
                .map(AccountListVoRow::getMarketingOccupancyTaskId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        return accountGroupMapper.selectMarketingOccupancyTasksByIds(List.copyOf(taskIds)).stream()
                .collect(Collectors.toMap(
                        AccountMarketingOccupancyTaskRow::getTaskId,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
    }

    /**
     * 根据任务状态和持久化锁类型派生账号列表颜色标签。
     *
     * <p>待释放优先于暂停占用，两者均优先于分组持久化的基础营销类型。</p>
     *
     * @param row 当前账号及所属分组的锁字段
     * @param tasksById 当前页涉及的营销任务状态
     * @return 前端营销占用类型 key
     */
    private String resolveOccupancyType(AccountListVoRow row,
                                        Map<Long, AccountMarketingOccupancyTaskRow> tasksById) {
        AccountMarketingOccupancyTaskRow task = tasksById.get(row.getMarketingOccupancyTaskId());
        String overrideType = task == null ? null : task.getOccupancyOverrideType();
        return AccountMarketingOccupancyType.resolve(
                row.getMarketingOccupancyTaskId(),
                row.getMarketingOccupancyType(),
                overrideType).name();
    }

    /**
     * 查询当前租户账号统计卡数据。
     *
     * <p>Mapper 负责单条聚合查询；未分配数量和受限总数由本方法按统一统计口径派生。</p>
     *
     * @return 账号总量、在线状态、限制状态及分配情况统计
     */
    @Override
    public AccountStatsVO getStats() {
        AccountStatsVoRow row = accountMapper.statsSummary();
        long unassigned = row.getTotal() - row.getAssigned();
        long restrictedTotal = row.getBanned()
                + row.getUnbound()
                + row.getMuted()
                + row.getExported()
                + row.getRestricted();
        return new AccountStatsVO(
                row.getTotal(),
                row.getOnline(),
                row.getOffline(),
                row.getPendingOnline(),
                restrictedTotal,
                row.getBanned(),
                row.getUnbound(),
                row.getMuted(),
                row.getExported(),
                row.getRestricted(),
                row.getRisk(),
                row.getAssigned(),
                unassigned
        );
    }

    /**
     * 批量读取当前租户内未软删账号的实时登录态。
     *
     * <p>查询只返回仍有效的账号；账号已存在但协议尚未上报登录态时，结果保留账号 ID，
     * 对应值为 {@code null}。空入参直接返回空 Map，不访问数据库。</p>
     *
     * @param accountIds 账号主键列表
     * @return 账号 ID 到当前登录态的只读映射；不存在或已软删账号不包含在结果中
     */
    @Override
    public Map<Long, Integer> getLoginStatesByIds(List<Long> accountIds) {
        if (accountIds == null || accountIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> states = new LinkedHashMap<>();
        for (AccountState row : accountMapper.selectActiveLoginStatesByIds(accountIds)) {
            states.put(row.getAccountId(), row.getLoginState());
        }
        return Collections.unmodifiableMap(states);
    }

    /**
     * 将指定账号人工迁移到目标分组。
     *
     * <p>事务内先按主键锁定账号及涉及的分组，再校验营销整组锁和活动建群任务引用。
     * 营销分组禁止迁入、迁出；活动建群账号分组允许迁入但禁止迁出。任务成功或失败后的
     * 系统自动转组不经过本人工入口。</p>
     *
     * @param ids 待迁移账号 ID；重复 ID 会去重
     * @param accountGroupId 目标分组 ID
     * @throws BusinessException 当参数非法、账号或分组不存在、分组被占用或并发状态变化时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void migrateGroup(List<Long> ids, Long accountGroupId) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 列表不能为空");
        }
        if (accountGroupId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "目标分组不能为空");
        }
        LinkedHashSet<Long> uniqueIds = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id == null) {
                throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 不能为空");
            }
            uniqueIds.add(id);
        }
        List<Long> normalizedIds = List.copyOf(uniqueIds);
        List<Account> accounts = accountMapper.selectActiveByIdsForUpdate(normalizedIds);
        if (accounts.size() != normalizedIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分账号不存在或已删除，请刷新后重试");
        }

        TreeSet<Long> involvedGroupIds = new TreeSet<>();
        involvedGroupIds.add(accountGroupId);
        accounts.stream()
                .map(Account::getAccountGroupId)
                .filter(java.util.Objects::nonNull)
                .forEach(involvedGroupIds::add);
        Map<Long, AccountGroup> groupsById = accountGroupMapper
                .selectByIdsForUpdate(List.copyOf(involvedGroupIds)).stream()
                .collect(Collectors.toMap(AccountGroup::getId, Function.identity()));
        AccountGroup targetGroup = groupsById.get(accountGroupId);
        if (targetGroup == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "目标分组不存在: " + accountGroupId);
        }
        if (targetGroup.getMarketingOccupancyTaskId() != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "目标分组正被营销任务占用，不允许迁入账号");
        }

        List<Long> sourceGroupIds = accounts.stream()
                .map(Account::getAccountGroupId)
                .filter(java.util.Objects::nonNull)
                .filter(groupId -> !groupId.equals(accountGroupId))
                .distinct()
                .sorted()
                .toList();
        boolean marketingSourceLocked = sourceGroupIds.stream()
                .map(groupsById::get)
                .filter(java.util.Objects::nonNull)
                .anyMatch(group -> group.getMarketingOccupancyTaskId() != null);
        if (marketingSourceLocked) {
            throw new BusinessException(ErrorCode.CONFLICT, "来源分组正被营销任务占用，不允许迁出账号");
        }
        if (!sourceGroupIds.isEmpty()
                && accountGroupMapper.countActiveBuilderGroupReferences(sourceGroupIds) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "建群账号分组正在执行任务，不允许迁出账号");
        }

        long now = System.currentTimeMillis();
        int updated = accountMapper.migrateGroup(normalizedIds, accountGroupId, now);
        if (updated != normalizedIds.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "账号分组状态已变化，请刷新后重试");
        }
        log.info("账号批量迁移分组 groupId={} count={} ids={}", accountGroupId, updated, normalizedIds);
    }

    /**
     * 按严格状态口径批量软删除账号。
     *
     * <p>仅允许删除封禁、导出或解绑且未进入任务的账号。校验采用全或无语义，
     * 任一账号不满足条件时不执行任何软删除。</p>
     *
     * @param ids 待删除账号 ID
     * @throws BusinessException 当 ID 列表为空或任一账号不满足删除条件时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 列表不能为空");
        }
        List<AccountDeleteGateRow> rows = accountMapper.selectStatesByIds(ids);
        // 全或无:先全量校验,任一不满足整批拒删
        for (AccountDeleteGateRow row : rows) {
            if (!isDeletable(row)) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "仅导出/封禁/解绑状态且不在任务的账号可删除(账号 " + row.getId() + " 不满足条件)");
            }
        }
        long now = System.currentTimeMillis();
        int deleted = accountMapper.batchSoftDelete(ids, now);
        log.info("账号批量软删除 count={} ids={}", deleted, ids);
    }

    /**
     * 判断单个账号是否满足严格删除口径。
     *
     * @param row 账号状态及任务占用投影
     * @return 账号处于可删除状态且未进入任务时返回 {@code true}
     */
    private boolean isDeletable(AccountDeleteGateRow row) {
        return row.getAccountState() != null
                && DELETABLE_STATES.contains(row.getAccountState())
                && row.getDispatchedAt() == null;
    }
}
