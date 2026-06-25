package com.armada.account.service.impl;

import com.armada.account.converter.AccountConverter;
import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.model.dto.AccountGroupDTO;
import com.armada.account.model.dto.AccountGroupQuery;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.model.vo.AccountGroupVO;
import com.armada.account.service.AccountGroupService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 账号分组业务实现。
 *
 * <p>租户隔离由 MyBatis 租户拦截器透明完成,本类不手写 tenant_id。</p>
 * <p>时间字段为 BIGINT epoch 毫秒,insert/update 时由调用方显式传入,无需回查。</p>
 */
@Service
public class AccountGroupServiceImpl implements AccountGroupService {

    private static final Logger log = LoggerFactory.getLogger(AccountGroupServiceImpl.class);

    /** 批量删除上限:防止一次删除过多造成锁竞争。 */
    private static final int BATCH_DELETE_MAX = 100;

    /** 系统默认分组名称。 */
    private static final String SYSTEM_GROUP_NAME = "系统默认分组";

    /** system_builtin=1:系统内置分组(不可改名/不可删除)。 */
    private static final int SYSTEM_BUILTIN_YES = 1;

    /** system_builtin=0:用户自建分组。 */
    private static final int SYSTEM_BUILTIN_NO = 0;

    private final AccountGroupMapper mapper;
    private final AccountConverter converter;

    public AccountGroupServiceImpl(AccountGroupMapper mapper, AccountConverter converter) {
        this.mapper = mapper;
        this.converter = converter;
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:list 开头懒创建系统默认分组;先取总数,为 0 时直接返回空页省掉一次必然空结果的列表查询;
     * 分页与筛选全部由 Mapper 的 SQL 下推,不在内存里裁剪。</p>
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
     * {@inheritDoc}
     *
     * <p>实现要点:① 名称非空;② 活跃重名拒绝;③ 命中同名软删分组则复活并更新;
     * ④ 否则插入新行。时间字段 BIGINT 由调用方 set,无需回查。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AccountGroupVO create(AccountGroupDTO dto) {
        if (!StringUtils.hasText(dto.name())) {
            throw new BusinessException(ErrorCode.VALIDATION, "分组名称不能为空");
        }
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
                    0,
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
                    0,
                    0L,
                    0L,
                    now,
                    now
            );
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:名称非空;分组存在;系统内置分组不可改名;重名校验排除自身。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, AccountGroupDTO dto) {
        if (!StringUtils.hasText(dto.name())) {
            throw new BusinessException(ErrorCode.VALIDATION, "分组名称不能为空");
        }
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
     * {@inheritDoc}
     *
     * <p>实现要点:ids 数量须 1..{@value #BATCH_DELETE_MAX};逐个校验非系统组且组内无账号(全或无闸门),
     * 全部通过才调 softDeleteByIds,任一不满足整批抛 VALIDATION。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > BATCH_DELETE_MAX) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "ids 数量须为 1.." + BATCH_DELETE_MAX);
        }
        // 全或无:先全量校验,任一不满足则整批拒删
        for (Long id : ids) {
            AccountGroup group = mapper.selectById(id);
            if (group == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "分组不存在: " + id);
            }
            if (Integer.valueOf(SYSTEM_BUILTIN_YES).equals(group.getSystemBuiltin())) {
                throw new BusinessException(ErrorCode.VALIDATION, "系统默认分组不允许删除");
            }
            long count = mapper.countAccountsByGroupId(id);
            if (count > 0) {
                throw new BusinessException(ErrorCode.VALIDATION,
                        "请先清空分组内的账号再删除(分组 " + id + " 下有 " + count + " 个账号)");
            }
        }
        long now = System.currentTimeMillis();
        int n = mapper.softDeleteByIds(ids, now);
        log.info("账号分组批量删除 count={} ids={}", n, ids);
        return n;
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现要点:用 mapper.selectById 查活跃分组;为 null 则抛 NOT_FOUND;否则直接返回。</p>
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
     * {@inheritDoc}
     *
     * <p>实现要点:查 system_builtin=1 的分组;不存在则 insert 一条;
     * 并发场景撞 uq_tenant_name 唯一键时捕获 DuplicateKeyException 并重查。</p>
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
}
