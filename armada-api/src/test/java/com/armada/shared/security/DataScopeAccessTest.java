package com.armada.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DataScopeAccessTest {

    @AfterEach
    void clearContext() {
        DataScopeContext.clear();
    }

    @Test
    void selfAllowsOnlyMatchingOwnerWhileAllAlsoAllowsHistoricalOwner() {
        DataScope self = DataScope.self(7L);
        DataScope all = DataScope.all(8L);

        assertThat(DataScopeAccess.canAccess(self, 7L)).isTrue();
        assertThat(DataScopeAccess.canAccess(self, 8L)).isFalse();
        assertThat(DataScopeAccess.canAccess(self, null)).isFalse();
        assertThat(DataScopeAccess.canAccess(all, 7L)).isTrue();
        assertThat(DataScopeAccess.canAccess(all, null)).isTrue();
    }

    @Test
    void ownerMismatchIsHiddenAsNotFound() {
        assertThatThrownBy(() -> DataScopeAccess.requireCanAccess(
                DataScope.self(7L), 8L, "账号"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.NOT_FOUND.code()))
                .hasMessage("账号不存在");
    }

    @Test
    void missingAndSystemScopeAreDenied() {
        assertThatThrownBy(DataScopeAccess::requireCurrent)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.ACCESS_DENIED.code()));

        assertThatThrownBy(() -> DataScopeAccess.requireCanAccess(
                DataScope.system("account event"), 7L, "账号"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.ACCESS_DENIED.code()));
        assertThat(DataScopeAccess.canAccess(DataScope.system("account event"), 7L)).isFalse();
    }

    @Test
    void currentUserScopeMustExactlyMatchTrustedPrincipal() {
        AuthPrincipal user = principal(7L, List.of("USER"));
        AuthPrincipal admin = principal(7L, List.of("TENANT_ADMIN"));

        DataScopeContext.open(DataScope.self(7L));
        assertThat(DataScopeAccess.requireCurrentForPrincipal(user))
                .isEqualTo(DataScope.self(7L));
        assertThatThrownBy(() -> DataScopeAccess.requireCurrentForPrincipal(admin))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.ACCESS_DENIED.code()));

        DataScopeContext.clear();
        assertThatThrownBy(() -> DataScopeAccess.requireCurrentForPrincipal(user))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.ACCESS_DENIED.code()));
        assertThatThrownBy(() -> DataScopeAccess.requireCurrentForPrincipal(null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.AUTH_INVALID.code()));
    }

    @Test
    void aggregateReferencesMustHaveTheSameOwnerEvenForAdministrator() {
        assertThatThrownBy(() -> DataScopeAccess.requireSameOwner(
                List.of(7L, 8L), "任务分组"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION.code()))
                .hasMessage("任务分组归属不一致");

        DataScopeAccess.requireSameOwner(Arrays.asList(null, null), "历史分组");
    }

    @Test
    void historicalUnownedAggregateIsVisibleToAdminButCannotExecute() {
        assertThat(DataScopeAccess.canAccess(DataScope.all(9L), null)).isTrue();
        assertThatThrownBy(() -> DataScopeAccess.requireAssignedOwner(null, "账号"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.ACCESS_DENIED.code()))
                .hasMessageContaining("历史无归属账号不能执行");
    }

    @Test
    void privateAggregateCreationRequiresEveryResourceToBelongToActor() {
        DataScopeAccess.requireOwnedByActorForCreate(
                DataScope.self(7L), List.of(7L, 7L), "营销任务");

        assertThatThrownBy(() -> DataScopeAccess.requireOwnedByActorForCreate(
                DataScope.all(9L), List.of(7L, 7L), "营销任务"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.VALIDATION.code()))
                .hasMessage("营销任务只能使用当前操作者自己的资源");

        assertThatThrownBy(() -> DataScopeAccess.requireOwnedByActorForCreate(
                DataScope.system("task scheduler"), List.of(7L), "营销任务"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ErrorCode.ACCESS_DENIED.code()));
    }

    private static AuthPrincipal principal(long userId, List<String> roles) {
        return new AuthPrincipal(
                userId, 3L, "user-" + userId, "用户" + userId,
                "tenant-3", "租户3", roles, List.of());
    }
}
