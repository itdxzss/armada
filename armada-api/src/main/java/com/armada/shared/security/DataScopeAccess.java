package com.armada.shared.security;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.List;
import java.util.Objects;

/** 业务层对数据归属进行一致性校验的工具。 */
public final class DataScopeAccess {

    private DataScopeAccess() {
    }

    /** 返回当前范围；缺少范围时拒绝继续执行用户数据操作。 */
    public static DataScope requireCurrent() {
        return DataScopeContext.current().orElseThrow(
                () -> new BusinessException(ErrorCode.ACCESS_DENIED, "当前请求缺少数据访问范围"));
    }

    /**
     * 返回与可信认证身份完全一致的当前用户范围。
     *
     * <p>只比较 actor 不足以防止普通用户范围被误建成 ALL；这里同时校验由角色推导出的
     * SELF/ALL 模式。异步入口应从持久化聚合恢复 owner，不应调用本方法伪造 HTTP 身份。</p>
     */
    public static DataScope requireCurrentForPrincipal(AuthPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(ErrorCode.AUTH_INVALID);
        }
        DataScope current = requireCurrent();
        DataScope expected = DataScope.fromPrincipal(principal);
        if (!current.equals(expected)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "当前身份与数据访问范围不一致");
        }
        return current;
    }

    /** 校验单条资源是否属于当前范围；普通用户看不到历史空 owner 数据。 */
    public static void requireCanAccess(DataScope scope, Long ownerUserId, String resourceName) {
        if (scope.mode() == DataScopeMode.SYSTEM) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "后台范围不能直接访问用户私有数据");
        }
        if (scope.mode() == DataScopeMode.SELF
                && !scope.actorUserId().equals(ownerUserId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, resourceName + "不存在");
        }
    }

    /** 返回给定归属是否属于当前范围。 */
    public static boolean canAccess(DataScope scope, Long ownerUserId) {
        return scope.mode() == DataScopeMode.ALL
                || (scope.mode() == DataScopeMode.SELF
                && scope.actorUserId().equals(ownerUserId));
    }

    /**
     * 要求私有聚合已有可信 owner，供会产生协议命令或继续执行的入口使用。
     * 历史空 owner 数据可以由管理员查看，但在显式分配/转移能力上线前不能执行。
     */
    public static long requireAssignedOwner(Long ownerUserId, String resourceName) {
        if (ownerUserId == null || ownerUserId <= 0) {
            throw new BusinessException(
                    ErrorCode.ACCESS_DENIED,
                    "历史无归属" + resourceName + "不能执行，请重新创建或等待归属分配功能上线");
        }
        return ownerUserId;
    }

    /**
     * 校验同一个业务聚合引用的用户私有资源归属一致。
     *
     * <p>管理员可以访问所有 owner，但在共享/转移语义上线前，仍不能把
     * 不同 owner 的账号、分组或任务资源拼成一个新聚合。历史空 owner
     * 是真实的一种归属值，只能与其他空 owner 资源关联。</p>
     *
     * @param ownerUserIds 已存在资源的 owner 列表，可包含 null 历史归属
     * @param associationName 错误消息中的聚合名称
     */
    public static void requireSameOwner(List<Long> ownerUserIds, String associationName) {
        if (ownerUserIds == null || ownerUserIds.size() < 2) {
            return;
        }
        Long expected = ownerUserIds.get(0);
        if (ownerUserIds.stream().skip(1).anyMatch(owner -> !Objects.equals(expected, owner))) {
            throw new BusinessException(ErrorCode.VALIDATION, associationName + "归属不一致");
        }
    }

    /**
     * 新建私有聚合只能引用操作者本人资源；管理员全量可见不等于可以代其他用户创建。
     * 共享、转移或代创建上线后，应由显式授权模型替代本规则。
     */
    public static void requireOwnedByActorForCreate(
            DataScope scope,
            List<Long> ownerUserIds,
            String associationName) {
        if (scope == null || scope.mode() == DataScopeMode.SYSTEM) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "后台范围不能创建用户私有聚合");
        }
        Long actorUserId = scope.actorUserId();
        if (ownerUserIds != null
                && ownerUserIds.stream().anyMatch(owner -> !Objects.equals(actorUserId, owner))) {
            throw new BusinessException(
                    ErrorCode.VALIDATION,
                    associationName + "只能使用当前操作者自己的资源");
        }
    }
}
