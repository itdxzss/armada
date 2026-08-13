package com.armada.account.service.impl;

import com.armada.account.converter.AccountConverter;
import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.model.dto.AccountGroupDTO;
import com.armada.account.model.dto.AccountGroupQuery;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.model.enums.AccountMarketingOccupancyType;
import com.armada.account.model.vo.AccountGroupVO;
import com.armada.account.model.vo.AccountGroupMarketingOccupancyVO;
import com.armada.account.model.vo.AccountMarketingOccupancyTaskRow;
import com.armada.account.service.AccountGroupService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 账号分组菜单业务实现。
 *
 * <p>负责分组分页、营销占用详情、分组增删改及拆分合并。涉及分组结构变化的操作
 * 会在事务内锁定目标分组，避免与营销任务启动抢锁并发冲突。</p>
 *
 * <p>租户隔离由 MyBatis 租户拦截器统一处理，本类不接收或拼接 {@code tenant_id}；
 * 时间字段统一由业务层写入 epoch 毫秒。</p>
 */
@Service
public class AccountGroupServiceImpl implements AccountGroupService {

    /** 账号分组业务日志。 */
    private static final Logger log = LoggerFactory.getLogger(AccountGroupServiceImpl.class);

    /** 批量删除上限:防止一次删除过多造成锁竞争。 */
    private static final int BATCH_DELETE_MAX = 100;

    /** 系统默认分组名称。 */
    private static final String SYSTEM_GROUP_NAME = "系统默认分组";

    /** system_builtin=1:系统内置分组(不可改名/不可删除)。 */
    private static final int SYSTEM_BUILTIN_YES = 1;

    /** system_builtin=0:用户自建分组。 */
    private static final int SYSTEM_BUILTIN_NO = 0;

    /** account_group.remark 字段长度。 */
    private static final int MAX_REMARK_LENGTH = 255;

    /** 账号分组及营销占用数据访问。 */
    private final AccountGroupMapper mapper;

    /** 账号分组查询投影转换器。 */
    private final AccountConverter converter;

    /**
     * 创建账号分组业务服务。
     *
     * @param mapper 账号分组及营销占用数据访问
     * @param converter 账号分组查询投影转换器
     */
    public AccountGroupServiceImpl(AccountGroupMapper mapper, AccountConverter converter) {
        this.mapper = mapper;
        this.converter = converter;
    }

    /**
     * 分页查询当前租户的账号分组。
     *
     * <p>首次查询时保证系统默认分组已经创建。分页、筛选和账号统计全部由 SQL 完成；
     * 总数为零时不再执行列表查询。</p>
     *
     * @param query 分组名称筛选及分页参数
     * @return 当前页账号分组及总数
     */
    @Override
    public PageResult<AccountGroupVO> list(AccountGroupQuery query) {
        ensureSystemGroup();
        long total = mapper.countPage(query);
        List<AccountGroupVO> rows = total == 0
                ? List.of()
                : converter.toGroupVOList(mapper.selectPage(query));
        log.info("账号分组列表查询 total={} page={} pageSize={}", total, query.getPage(), query.getPageSize());
        return PageResult.of(rows, query.getPage(), query.getPageSize(), total);
    }

    /**
     * 查询指定账号分组当前的营销整组占用详情。
     *
     * <p>接口只在用户点击分组名称时加载任务名称和账号调用统计。若锁记录仍存在但
     * 任务数据异常缺失，则保留锁归属字段并记录告警，不把分组展示为空闲。</p>
     *
     * @param groupId 账号分组 ID
     * @return 占用类型、任务信息、资源状态及营销账号调用统计
     * @throws BusinessException 当分组不存在或已删除时抛出
     */
    @Override
    public AccountGroupMarketingOccupancyVO marketingOccupancy(Long groupId) {
        AccountMarketingOccupancyTaskRow row = mapper.selectMarketingOccupancyByGroupId(groupId);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "分组不存在: " + groupId);
        }
        if (row.getTaskId() != null && row.getTaskBusinessType() == null) {
            log.warn("账号分组营销占用归属任务缺失 groupId={} taskId={} occupancyType={}",
                    groupId, row.getTaskId(), row.getOccupancyType());
        }
        return new AccountGroupMarketingOccupancyVO(
                row.getGroupId(),
                resolveOccupancyType(row),
                row.getTaskBusinessType(),
                row.getTaskId(),
                row.getTaskName(),
                row.getTaskStatus(),
                row.getResourceStatus(),
                row.getLockedAt(),
                valueOrZero(row.getMarketingAccountTotalCount()),
                valueOrZero(row.getMarketingAccountUsedCount()));
    }

    /**
     * 按 Mapper 覆盖状态和持久化业务类型派生前端占用标签。
     *
     * <p>释放中优先于暂停，两者均优先于基础营销业务类型。</p>
     *
     * @param row 分组占用及任务状态投影
     * @return 前端占用类型 key
     */
    private String resolveOccupancyType(AccountMarketingOccupancyTaskRow row) {
        return AccountMarketingOccupancyType.resolve(
                row.getTaskId(),
                row.getOccupancyType(),
                row.getOccupancyOverrideType()).name();
    }

    /**
     * 将 Mapper 可空聚合值转换为稳定的接口计数。
     *
     * @param value Mapper 聚合结果
     * @return 原始计数；值为空时返回零
     */
    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 创建账号分组，或复活同名的软删除分组。
     *
     * <p>活跃分组不允许重名；命中同名软删除记录时沿用原分组 ID 和创建时间，
     * 恢复记录后更新名称及备注。</p>
     *
     * @param dto 分组名称和备注
     * @return 新建或复活后的分组信息
     * @throws BusinessException 当名称为空、备注超长或活跃分组重名时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountGroupVO create(AccountGroupDTO dto) {
        validatePayload(dto);
        if (mapper.selectActiveByName(dto.name()) != null) {
            throw new BusinessException(ErrorCode.VALIDATION, "分组名称已存在: " + dto.name());
        }
        AccountGroup deleted = mapper.selectDeletedByName(dto.name());
        long now = System.currentTimeMillis();
        AccountGroup row = new AccountGroup();
        row.setName(dto.name());
        row.setRemark(dto.remark());
        row.setSystemBuiltin(SYSTEM_BUILTIN_NO);

        if (deleted != null) {
            // 复活软删分组:复原 deleted_at + 更新基本信息
            row.setId(deleted.getId());
            row.setUpdatedAt(now);
            mapper.reviveById(deleted.getId());
            mapper.updateProfile(row);
            log.info("账号分组复活 id={} name={}", deleted.getId(), dto.name());
            // 复活场景:createdAt 沿用原有值(BIGINT 已在库里,row 没设 createdAt 所以取 deleted.getCreatedAt())
            return new AccountGroupVO(
                    deleted.getId(),
                    dto.name(),
                    dto.remark(),
                    null,
                    null,
                    null,
                    0,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    deleted.getCreatedAt(),
                    now
            );
        } else {
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            mapper.insert(row);
            log.info("账号分组已创建 id={} name={}", row.getId(), dto.name());
            return new AccountGroupVO(
                    row.getId(),
                    dto.name(),
                    dto.remark(),
                    null,
                    null,
                    null,
                    0,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    now,
                    now
            );
        }
    }

    /**
     * 修改用户自建账号分组的名称和备注。
     *
     * @param id 待修改分组 ID
     * @param dto 新的分组名称和备注
     * @throws BusinessException 当分组不存在、名称非法、名称重复或目标是系统分组时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AccountGroupDTO dto) {
        validatePayload(dto);
        AccountGroup cur = mapper.selectById(id);
        if (cur == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "分组不存在: " + id);
        }
        if (Integer.valueOf(SYSTEM_BUILTIN_YES).equals(cur.getSystemBuiltin())) {
            throw new BusinessException(ErrorCode.VALIDATION, "系统默认分组不允许修改名称");
        }
        AccountGroup other = mapper.selectActiveByName(dto.name());
        if (other != null && !other.getId().equals(id)) {
            throw new BusinessException(ErrorCode.VALIDATION, "分组名称已存在: " + dto.name());
        }
        AccountGroup row = new AccountGroup();
        row.setId(id);
        row.setName(dto.name());
        row.setRemark(dto.remark());
        row.setUpdatedAt(System.currentTimeMillis());
        mapper.updateProfile(row);
        log.info("账号分组已更新 id={} name={}", id, dto.name());
    }

    /**
     * 批量软删除空闲的用户自建账号分组。
     *
     * <p>先按 ID 升序读取并全量校验。最终软删除 SQL 原子校验营销占用状态；任一分组
     * 不存在、属于系统、正被营销任务占用或仍有账号时，整批操作回滚。</p>
     *
     * @param ids 待删除分组 ID，数量范围为 1..{@value #BATCH_DELETE_MAX}
     * @return 实际软删除的分组数量
     * @throws BusinessException 当参数或任一分组不满足删除条件时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > BATCH_DELETE_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "ids 数量须为 1.." + BATCH_DELETE_MAX);
        }
        if (ids.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException(ErrorCode.VALIDATION, "分组 ID 不能为空");
        }
        List<Long> normalizedIds = List.copyOf(new TreeSet<>(ids));
        List<AccountGroup> groups = new ArrayList<>(normalizedIds.size());
        for (Long id : normalizedIds) {
            AccountGroup group = mapper.selectById(id);
            if (group == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "部分分组不存在，请刷新后重试");
            }
            groups.add(group);
        }
        // 全或无:先全量校验,任一不满足则整批拒删
        for (AccountGroup group : groups) {
            Long id = group.getId();
            if (Integer.valueOf(SYSTEM_BUILTIN_YES).equals(group.getSystemBuiltin())) {
                throw new BusinessException(ErrorCode.VALIDATION, "系统默认分组不允许删除");
            }
            if (group.getMarketingOccupancyTaskId() != null) {
                throw new BusinessException(ErrorCode.CONFLICT, "分组正被营销任务占用，不允许删除");
            }
            long count = mapper.countAccountsByGroupId(id);
            if (count > 0) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "请先清空分组内的账号再删除(分组 " + id + " 下有 " + count + " 个账号)");
            }
        }
        long now = System.currentTimeMillis();
        int n = mapper.softDeleteByIds(normalizedIds, now);
        if (n != normalizedIds.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "分组占用状态已变化，请刷新后重试");
        }
        log.info("账号分组批量删除 count={} ids={}", n, normalizedIds);
        return n;
    }

    /**
     * 将一个未被营销任务占用的账号分组平均拆成指定数量的新分组。
     *
     * <p>事务内先锁定来源分组，防止任务启动与拆分并发修改同一分组；
     * 账号迁移和来源分组软删除必须全部成功，否则整体回滚。</p>
     *
     * @param groupId 来源分组 ID
     * @param groupCount 目标分组数量
     * @throws BusinessException 当分组不存在、为空、正被占用或拆分数量非法时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void split(Long groupId, Integer groupCount) {
        if (groupId == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "分组 ID 不能为空");
        }
        AccountGroup source = lockMutableGroups(List.of(groupId)).get(0);
        List<Long> accountIds = mapper.selectAccountIdsByGroupId(groupId);
        if (accountIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "空分组不允许拆分");
        }
        if (groupCount == null || groupCount < 2 || groupCount > accountIds.size()) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "拆分数量须为 2.." + accountIds.size());
        }
        List<Long> targetIds = new java.util.ArrayList<>(groupCount);
        for (int i = 1; i <= groupCount; i++) {
            targetIds.add(create(new AccountGroupDTO(source.getName() + "-" + i, source.getRemark())).id());
        }
        int baseSize = accountIds.size() / groupCount;
        int remainder = accountIds.size() % groupCount;
        int offset = 0;
        long now = System.currentTimeMillis();
        for (int i = 0; i < groupCount; i++) {
            int size = baseSize + (i < remainder ? 1 : 0);
            mapper.updateAccountGroup(accountIds.subList(offset, offset + size), targetIds.get(i), now);
            offset += size;
        }
        if (mapper.softDeleteByIds(List.of(groupId), now) != 1) {
            throw new BusinessException(ErrorCode.CONFLICT, "分组占用状态已变化，请刷新后重试");
        }
        log.info("账号分组拆分完成 sourceGroupId={} targetGroupCount={} accountCount={}",
                groupId, groupCount, accountIds.size());
    }

    /**
     * 将多个未被营销任务占用的账号分组合并到请求中的首个分组。
     *
     * <p>事务内按 ID 升序锁定全部相关分组；账号迁移和来源分组软删除
     * 必须全部成功，否则整体回滚。</p>
     *
     * @param groupIds 待合并分组 ID，首个 ID 为目标分组
     * @throws BusinessException 当分组不存在、重复、包含系统分组或正被占用时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void merge(List<Long> groupIds) {
        if (groupIds == null
                || groupIds.size() < 2
                || groupIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(groupIds).size() != groupIds.size()) {
            throw new BusinessException(ErrorCode.VALIDATION, "合并至少需要两个不重复分组");
        }
        lockMutableGroups(groupIds);
        Long targetGroupId = groupIds.get(0);
        List<Long> sourceGroupIds = groupIds.subList(1, groupIds.size());
        long now = System.currentTimeMillis();
        int accountCount = mapper.mergeAccounts(sourceGroupIds, targetGroupId, now);
        if (mapper.softDeleteByIds(sourceGroupIds, now) != sourceGroupIds.size()) {
            throw new BusinessException(ErrorCode.CONFLICT, "分组占用状态已变化，请刷新后重试");
        }
        log.info("账号分组合并完成 targetGroupId={} sourceGroupCount={} accountCount={}",
                targetGroupId, sourceGroupIds.size(), accountCount);
    }

    /**
     * 校验分组是否允许拆分或合并。
     *
     * @param group 已在当前事务内锁定的分组
     * @throws BusinessException 当分组是系统分组或正被营销任务占用时抛出
     */
    private void requireMutableGroup(AccountGroup group) {
        if (Integer.valueOf(SYSTEM_BUILTIN_YES).equals(group.getSystemBuiltin())) {
            throw new BusinessException(ErrorCode.VALIDATION, "系统默认分组不允许拆分或合并");
        }
        if (group.getMarketingOccupancyTaskId() != null) {
            throw new BusinessException(ErrorCode.CONFLICT, "分组正被营销任务占用，不允许拆分或合并");
        }
    }

    /**
     * 锁定并校验一组拆分、合并目标均允许结构变更。
     *
     * @param groupIds 待锁定分组 ID
     * @return 按主键升序锁定的分组
     * @throws BusinessException 当分组不存在或不允许结构变更时抛出
     */
    private List<AccountGroup> lockMutableGroups(List<Long> groupIds) {
        List<AccountGroup> groups = lockExistingGroups(groupIds);
        groups.forEach(this::requireMutableGroup);
        return groups;
    }

    /**
     * 按主键升序锁定分组，并保证请求中的每个分组都存在。
     *
     * @param groupIds 待锁定分组 ID
     * @return 按主键升序锁定的分组
     * @throws BusinessException 当任一分组不存在或已删除时抛出
     */
    private List<AccountGroup> lockExistingGroups(List<Long> groupIds) {
        List<Long> sortedIds = List.copyOf(new TreeSet<>(groupIds));
        List<AccountGroup> groups = mapper.selectByIdsForUpdate(sortedIds);
        if (groups.size() != sortedIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "部分分组不存在，请刷新后重试");
        }
        return groups;
    }

    /**
     * 获取指定活跃分组，供账号导入等业务执行前置校验。
     *
     * @param id 分组 ID
     * @return 对应的活跃分组实体
     * @throws BusinessException 当分组不存在或已删除时抛出
     */
    @Override
    public AccountGroup requireExisting(Long id) {
        AccountGroup group = mapper.selectById(id);
        if (group == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "目标分组不存在: " + id);
        }
        return group;
    }

    /**
     * 获取当前租户的系统默认分组，不存在时幂等创建。
     *
     * <p>并发创建命中租户内名称唯一键时，当前事务重新查询已经由其他请求创建的分组。</p>
     *
     * @return 当前租户的系统默认分组
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountGroup ensureSystemGroup() {
        AccountGroup existing = mapper.selectSystemBuiltin();
        if (existing != null) {
            return existing;
        }
        long now = System.currentTimeMillis();
        AccountGroup row = new AccountGroup();
        row.setName(SYSTEM_GROUP_NAME);
        row.setSystemBuiltin(SYSTEM_BUILTIN_YES);
        row.setRemark("系统自动创建,不可删除");
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            mapper.insert(row);
            log.info("系统默认分组已懒创建 id={}", row.getId());
        } catch (DuplicateKeyException e) {
            // 并发场景:另一线程已抢先 insert,重查即可
            log.debug("系统默认分组并发创建冲突,重查");
            row = mapper.selectSystemBuiltin();
        }
        return row;
    }

    /**
     * 校验账号分组名称和备注长度。
     *
     * @param dto 待校验分组信息
     * @throws BusinessException 当名称为空或备注超过数据库字段长度时抛出
     */
    private static void validatePayload(AccountGroupDTO dto) {
        if (!StringUtils.hasText(dto.name())) {
            throw new BusinessException(ErrorCode.VALIDATION, "分组名称不能为空");
        }
        if (dto.remark() != null && charCount(dto.remark()) > MAX_REMARK_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "备注不能超过" + MAX_REMARK_LENGTH + "个字符");
        }
    }

    /**
     * 按 Unicode 码点统计字符数量，避免代理对被重复计数。
     *
     * @param value 待统计文本
     * @return Unicode 字符数量
     */
    private static int charCount(String value) {
        return value.codePointCount(0, value.length());
    }
}
