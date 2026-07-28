package com.armada.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.armada.admin.mapper.SysRoleMapper;
import com.armada.admin.mapper.SysUserMapper;
import com.armada.admin.model.dto.UserCreateDTO;
import com.armada.admin.model.dto.UserUpdateDTO;
import com.armada.admin.model.entity.SysRole;
import com.armada.admin.model.entity.SysUser;
import com.armada.admin.model.vo.UserVO;
import com.armada.admin.service.impl.UserManagementServiceImpl;
import com.armada.shared.exception.BusinessException;
import com.armada.platform.auth.service.SessionService;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserManagementServiceImplTest {

    @Mock
    private SysUserMapper userMapper;

    @Mock
    private SysRoleMapper roleMapper;

    @Mock
    private SessionService sessionService;

    private PasswordEncoder passwordEncoder;
    private UserManagementServiceImpl service;

    @BeforeEach
    void setUp() {
        passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        service = new UserManagementServiceImpl(userMapper, roleMapper, passwordEncoder, sessionService);
    }

    @Test
    void changesOwnPasswordAfterVerifyingCurrentPassword() {
        SysUser user = user(7L, 1);
        user.setPasswordHash(passwordEncoder.encode("old-password1"));
        when(userMapper.findById(7L)).thenReturn(Optional.of(user));

        service.changeOwnPassword(7L, "old-password1", "new-password2");

        verify(userMapper).updatePasswordHash(eq(7L), argThat(hash ->
                passwordEncoder.matches("new-password2", hash)), anyLong());
        verify(sessionService).invalidateUser(7L);
    }

    @Test
    void rejectsWrongCurrentPasswordWithoutChangingAnything() {
        SysUser user = user(7L, 1);
        user.setPasswordHash(passwordEncoder.encode("old-password1"));
        when(userMapper.findById(7L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.changeOwnPassword(7L, "wrong-password", "new-password2"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("当前密码错误");

        verify(userMapper, never()).updatePasswordHash(anyLong(), anyString(), anyLong());
        verify(sessionService, never()).invalidateUser(anyLong());
    }

    @Test
    void createRejectsPasswordShorterThanEightCharacters() {
        UserCreateDTO request = new UserCreateDTO("tester", "测试用户", "1234567", List.of());

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("8至18");

        verify(userMapper, never()).insert(any());
    }

    @Test
    void createStoresDelegatingBcryptHashAndNeverReturnsIt() {
        when(userMapper.countByUsername("tester")).thenReturn(0L);
        org.mockito.Mockito.doAnswer(invocation -> {
            SysUser user = invocation.getArgument(0);
            user.setId(99L);
            return 1;
        }).when(userMapper).insert(any());

        UserVO result = service.create(new UserCreateDTO(
                "tester", "测试用户", "password-123", List.of()));

        org.mockito.ArgumentCaptor<SysUser> captor = org.mockito.ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).startsWith("{bcrypt}");
        assertThat(passwordEncoder.matches("password-123", captor.getValue().getPasswordHash())).isTrue();
        assertThat(Arrays.stream(UserVO.class.getRecordComponents()).map(component -> component.getName()))
                .doesNotContain("password", "passwordHash");
        assertThat(result.username()).isEqualTo("tester");
    }

    @Test
    void updateRetainsAlreadyBoundDisabledRole() {
        SysUser user = user(7L, 1);
        SysRole disabledExistingRole = role(1L, "LEGACY", 0);
        when(userMapper.findById(7L)).thenReturn(Optional.of(user));
        when(userMapper.findRoleIdsByUserId(7L)).thenReturn(List.of(1L));
        when(roleMapper.findByIds(List.of(1L))).thenReturn(List.of(disabledExistingRole));

        service.update(7L, new UserUpdateDTO("新昵称", List.of(1L)));

        verify(userMapper).replaceUserRoles(7L, List.of(1L));
    }

    @Test
    void updateRejectsNewBindingToDisabledRole() {
        SysUser user = user(7L, 1);
        SysRole enabledExistingRole = role(1L, "NORMAL", 1);
        SysRole disabledNewRole = role(2L, "DISABLED", 0);
        when(userMapper.findById(7L)).thenReturn(Optional.of(user));
        when(userMapper.findRoleIdsByUserId(7L)).thenReturn(List.of(1L));
        when(roleMapper.findByIds(List.of(1L, 2L))).thenReturn(List.of(enabledExistingRole, disabledNewRole));

        assertThatThrownBy(() -> service.update(7L, new UserUpdateDTO("用户", List.of(1L, 2L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已禁用角色");

        verify(userMapper, never()).replaceUserRoles(anyLong(), any());
    }

    @Test
    void disablingLastEnabledTenantAdminIsRejected() {
        SysUser user = user(7L, 1);
        when(userMapper.findById(7L)).thenReturn(Optional.of(user));
        when(userMapper.hasRoleCode(7L, "TENANT_ADMIN")).thenReturn(true);
        when(userMapper.countEnabledUsersByRoleCode("TENANT_ADMIN")).thenReturn(1L);

        assertThatThrownBy(() -> service.changeStatus(7L, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最后一个启用的租户管理员");

        verify(userMapper, never()).updateStatus(anyLong(), org.mockito.ArgumentMatchers.anyInt(), anyLong());
    }

    @Test
    void unbindingLastEnabledTenantAdminRoleIsRejected() {
        SysUser user = user(7L, 1);
        when(userMapper.findById(7L)).thenReturn(Optional.of(user));
        when(userMapper.findRoleIdsByUserId(7L)).thenReturn(List.of(1L));
        when(userMapper.hasRoleCode(7L, "TENANT_ADMIN")).thenReturn(true);
        when(userMapper.countEnabledUsersByRoleCode("TENANT_ADMIN")).thenReturn(1L);

        assertThatThrownBy(() -> service.update(7L, new UserUpdateDTO("管理员", List.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最后一个启用的租户管理员");

        verify(userMapper, never()).replaceUserRoles(anyLong(), any());
    }

    private static SysUser user(long id, int status) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setUsername("tester");
        user.setNickname("测试用户");
        user.setStatus(status);
        return user;
    }

    private static SysRole role(long id, String code, int status) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setRoleCode(code);
        role.setStatus(status);
        return role;
    }
}
