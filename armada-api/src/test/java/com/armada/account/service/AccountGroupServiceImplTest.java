package com.armada.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.account.converter.AccountConverter;
import com.armada.account.mapper.AccountGroupMapper;
import com.armada.account.model.dto.AccountGroupDTO;
import com.armada.account.model.dto.AccountGroupQuery;
import com.armada.account.model.entity.AccountGroup;
import com.armada.account.model.vo.AccountGroupVO;
import com.armada.account.model.vo.AccountGroupVoRow;
import com.armada.account.service.impl.AccountGroupServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * AccountGroupService 业务规则单测(mock mapper/converter,验证业务逻辑;SQL/真库另由 DbTest 覆盖)。
 */
@ExtendWith(MockitoExtension.class)
class AccountGroupServiceImplTest {

    @Mock
    private AccountGroupMapper mapper;

    @Mock
    private AccountConverter converter;

    @InjectMocks
    private AccountGroupServiceImpl service;

    // ---- list ----

    @Test
    void list_returnsEmptyPage_whenTotalZero() {
        AccountGroupQuery q = new AccountGroupQuery();
        when(mapper.countPage(q)).thenReturn(0L);

        PageResult<AccountGroupVO> result = service.list(q);

        assertThat(result.total()).isEqualTo(0L);
        assertThat(result.list()).isEmpty();
        verify(mapper, never()).selectPage(any());
    }

    @Test
    void list_callsSelectPage_whenTotalNonZero() {
        AccountGroupQuery q = new AccountGroupQuery();
        AccountGroupVoRow row = new AccountGroupVoRow();
        AccountGroupVO vo = new AccountGroupVO(1L, "分组A", null, 0, 0L, 0L, null, null);
        when(mapper.countPage(q)).thenReturn(1L);
        when(mapper.selectPage(q)).thenReturn(List.of(row));
        when(converter.toGroupVOList(List.of(row))).thenReturn(List.of(vo));

        PageResult<AccountGroupVO> result = service.list(q);

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.list()).hasSize(1);
    }

    @Test
    void list_callsEnsureSystemGroup() {
        // ensureSystemGroup 在 list 开头调用;这里 selectSystemBuiltin 返回已存在 → 不 insert
        AccountGroupQuery q = new AccountGroupQuery();
        AccountGroup sys = new AccountGroup();
        sys.setId(1L);
        sys.setSystemBuiltin(1);
        when(mapper.selectSystemBuiltin()).thenReturn(sys);
        when(mapper.countPage(q)).thenReturn(0L);

        service.list(q);

        verify(mapper).selectSystemBuiltin();
        verify(mapper, never()).insert(any());
    }

    // ---- ensureSystemGroup ----

    @Test
    void ensureSystemGroup_createsWhenMissing() {
        when(mapper.selectSystemBuiltin()).thenReturn(null);
        doAnswer(inv -> {
            AccountGroup row = inv.getArgument(0);
            row.setId(1L);
            return 1;
        }).when(mapper).insert(any());

        AccountGroup result = service.ensureSystemGroup();

        verify(mapper).insert(any());
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getSystemBuiltin()).isEqualTo(1);
    }

    @Test
    void ensureSystemGroup_returnsExisting_whenPresent() {
        AccountGroup existing = new AccountGroup();
        existing.setId(5L);
        existing.setSystemBuiltin(1);
        when(mapper.selectSystemBuiltin()).thenReturn(existing);

        AccountGroup result = service.ensureSystemGroup();

        verify(mapper, never()).insert(any());
        assertThat(result.getId()).isEqualTo(5L);
    }

    // ---- create ----

    @Test
    void create_blankName_throws() {
        assertThatThrownBy(() -> service.create(new AccountGroupDTO("  ", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("名称不能为空");
        verify(mapper, never()).insert(any());
    }

    @Test
    void create_activeNameExists_throws() {
        AccountGroup existing = new AccountGroup();
        existing.setId(99L);
        when(mapper.selectActiveByName("已存在")).thenReturn(existing);

        assertThatThrownBy(() -> service.create(new AccountGroupDTO("已存在", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
        verify(mapper, never()).insert(any());
    }

    @Test
    void create_deletedNameExists_reviveAndUpdate_notInsert() {
        when(mapper.selectActiveByName("复活分组")).thenReturn(null);
        AccountGroup deleted = new AccountGroup();
        deleted.setId(10L);
        deleted.setName("复活分组");
        when(mapper.selectDeletedByName("复活分组")).thenReturn(deleted);

        AccountGroupVO vo = service.create(new AccountGroupDTO("复活分组", "备注"));

        verify(mapper).reviveById(10L);
        verify(mapper).updateProfile(any());
        verify(mapper, never()).insert(any());
        assertThat(vo.id()).isEqualTo(10L);
    }

    @Test
    void create_newName_inserts() {
        when(mapper.selectActiveByName("新分组")).thenReturn(null);
        when(mapper.selectDeletedByName("新分组")).thenReturn(null);
        doAnswer(inv -> {
            AccountGroup row = inv.getArgument(0);
            row.setId(99L);
            return 1;
        }).when(mapper).insert(any());

        AccountGroupVO vo = service.create(new AccountGroupDTO("新分组", null));

        verify(mapper).insert(any());
        assertThat(vo.id()).isEqualTo(99L);
        assertThat(vo.name()).isEqualTo("新分组");
    }

    // ---- update ----

    @Test
    void update_blankName_throws() {
        assertThatThrownBy(() -> service.update(1L, new AccountGroupDTO("  ", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("名称不能为空");
        verify(mapper, never()).updateProfile(any());
    }

    @Test
    void update_notFound_throws() {
        when(mapper.selectById(42L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(42L, new AccountGroupDTO("X", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
        verify(mapper, never()).updateProfile(any());
    }

    @Test
    void update_systemBuiltin_throws() {
        AccountGroup sys = new AccountGroup();
        sys.setId(1L);
        sys.setSystemBuiltin(1);
        when(mapper.selectById(1L)).thenReturn(sys);

        assertThatThrownBy(() -> service.update(1L, new AccountGroupDTO("新名称", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("系统默认分组");
        verify(mapper, never()).updateProfile(any());
    }

    @Test
    void update_conflictingName_throws() {
        AccountGroup cur = new AccountGroup();
        cur.setId(1L);
        cur.setSystemBuiltin(0);
        when(mapper.selectById(1L)).thenReturn(cur);

        AccountGroup other = new AccountGroup();
        other.setId(2L);
        when(mapper.selectActiveByName("冲突名")).thenReturn(other);

        assertThatThrownBy(() -> service.update(1L, new AccountGroupDTO("冲突名", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已存在");
        verify(mapper, never()).updateProfile(any());
    }

    @Test
    void update_sameName_allowsSelf() {
        AccountGroup cur = new AccountGroup();
        cur.setId(1L);
        cur.setSystemBuiltin(0);
        when(mapper.selectById(1L)).thenReturn(cur);
        // 查到的是自身
        AccountGroup self = new AccountGroup();
        self.setId(1L);
        when(mapper.selectActiveByName("同名")).thenReturn(self);

        service.update(1L, new AccountGroupDTO("同名", null));

        verify(mapper).updateProfile(any());
    }

    // ---- batchDelete ----

    @Test
    void batchDelete_nullIds_throws() {
        assertThatThrownBy(() -> service.batchDelete(null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void batchDelete_emptyIds_throws() {
        assertThatThrownBy(() -> service.batchDelete(List.of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void batchDelete_exceedsMax_throws() {
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 101)
                .boxed().toList();
        assertThatThrownBy(() -> service.batchDelete(ids))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void batchDelete_rejectsWhenGroupHasAccounts() {
        AccountGroup g = new AccountGroup();
        g.setId(9L);
        g.setSystemBuiltin(0);
        when(mapper.selectById(9L)).thenReturn(g);
        when(mapper.countAccountsByGroupId(9L)).thenReturn(3L);

        assertThatThrownBy(() -> service.batchDelete(List.of(9L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("请先清空");
        verify(mapper, never()).softDeleteByIds(anyList(), anyLong());   // 全或无:一条挡则整批不删
    }

    @Test
    void batchDelete_rejectsSystemGroup() {
        AccountGroup sys = new AccountGroup();
        sys.setId(1L);
        sys.setSystemBuiltin(1);
        when(mapper.selectById(1L)).thenReturn(sys);

        assertThatThrownBy(() -> service.batchDelete(List.of(1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("系统默认分组");
        verify(mapper, never()).softDeleteByIds(anyList(), anyLong());
    }

    @Test
    void batchDelete_notFound_throws() {
        when(mapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.batchDelete(List.of(99L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不存在");
        verify(mapper, never()).softDeleteByIds(anyList(), anyLong());
    }

    @Test
    void batchDelete_valid_softDeletesCalled() {
        AccountGroup g1 = new AccountGroup();
        g1.setId(1L);
        g1.setSystemBuiltin(0);
        AccountGroup g2 = new AccountGroup();
        g2.setId(2L);
        g2.setSystemBuiltin(0);
        when(mapper.selectById(1L)).thenReturn(g1);
        when(mapper.selectById(2L)).thenReturn(g2);
        when(mapper.countAccountsByGroupId(1L)).thenReturn(0L);
        when(mapper.countAccountsByGroupId(2L)).thenReturn(0L);
        when(mapper.softDeleteByIds(anyList(), anyLong())).thenReturn(2);

        int result = service.batchDelete(List.of(1L, 2L));

        verify(mapper).softDeleteByIds(anyList(), anyLong());
        assertThat(result).isEqualTo(2);
    }

    // ---- requireExisting ----

    @Test
    void requireExisting_returnsGroup_whenPresent() {
        AccountGroup g = new AccountGroup();
        g.setId(7L);
        when(mapper.selectById(7L)).thenReturn(g);

        AccountGroup result = service.requireExisting(7L);

        assertThat(result.getId()).isEqualTo(7L);
        verify(mapper).selectById(7L);
    }

    @Test
    void requireExisting_throwsNotFound_whenMissing() {
        when(mapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.requireExisting(999L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("目标分组不存在");
        verify(mapper).selectById(999L);
    }
}
