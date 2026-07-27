package com.armada.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.admin.model.dto.UserCreateDTO;
import com.armada.shared.exception.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 用户密码规则测试，不依赖数据库或 Mock。 */
class UserPasswordPolicyTest {

    private final UserManagementServiceImpl service =
            new UserManagementServiceImpl(null, null, null, null);

    @Test
    void rejectsPasswordContainingOnlyLetters() {
        UserCreateDTO request = new UserCreateDTO("tester", "测试用户", "AdminAdmin", List.of());

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("至少两类");
    }

    @Test
    void rejectsPasswordContainingWhitespace() {
        UserCreateDTO request = new UserCreateDTO("tester", "测试用户", "admin 123", List.of());

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("8至18");
    }
}
