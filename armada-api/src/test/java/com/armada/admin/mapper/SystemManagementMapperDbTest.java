package com.armada.admin.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.armada.admin.model.entity.SysRole;
import com.armada.admin.model.entity.SysUser;
import com.armada.testsupport.DbTestBase;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 系统管理 Mapper 的租户隔离与关联替换真库测试。 */
class SystemManagementMapperDbTest extends DbTestBase {

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private SysRoleMapper roleMapper;

    @Autowired
    private SysMenuMapper menuMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void userLookupAndRoleReplacementStayInsideCurrentTenant() {
        long now = System.currentTimeMillis();
        SysUser user = new SysUser();
        user.setUsername("rbac-mapper-user");
        user.setNickname("Mapper测试用户");
        user.setPasswordHash("{noop}test-password");
        user.setStatus(1);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        assertThat(userMapper.insert(user)).isEqualTo(1);

        List<Long> enabledRoleIds = roleMapper.findEnabledIds();
        assertThat(enabledRoleIds).isNotEmpty();
        userMapper.replaceUserRoles(user.getId(), enabledRoleIds.subList(0, 1));

        assertThat(userMapper.findByUsername("rbac-mapper-user"))
                .get()
                .extracting(SysUser::getId)
                .isEqualTo(user.getId());
        assertThat(userMapper.findRoleIdsByUserId(user.getId()))
                .containsExactly(enabledRoleIds.get(0));

        Long otherTenantUserId = jdbc.queryForObject(
                "SELECT id FROM sys_user WHERE tenant_id=? AND username=?",
                Long.class,
                TEST_TENANT_ID,
                "rbac-mapper-user");
        assertThat(otherTenantUserId).isEqualTo(user.getId());
    }

    @Test
    void roleMenuReplacementStoresOnlySelectedMenuAndButtonNodes() {
        long now = System.currentTimeMillis();
        SysRole role = new SysRole();
        role.setRoleName("Mapper测试角色");
        role.setRoleCode("MAPPER_TEST_ROLE");
        role.setStatus(1);
        role.setIsSystem(0);
        role.setCreatedAt(now);
        role.setUpdatedAt(now);
        assertThat(roleMapper.insert(role)).isEqualTo(1);

        List<Long> grantableIds = menuMapper.findGrantableIds();
        assertThat(grantableIds).hasSizeGreaterThanOrEqualTo(2);
        List<Long> selected = grantableIds.subList(0, 2);
        menuMapper.replaceRoleMenus(role.getId(), selected);

        assertThat(roleMapper.findMenuIdsByRoleId(role.getId()))
                .containsExactlyInAnyOrderElementsOf(selected);
        assertThat(menuMapper.findAllOrdered())
                .isSortedAccordingTo((left, right) -> {
                    int parentCompare = Long.compare(left.getParentId(), right.getParentId());
                    return parentCompare != 0
                            ? parentCompare
                            : Integer.compare(left.getSortNo(), right.getSortNo());
                });
    }
}
