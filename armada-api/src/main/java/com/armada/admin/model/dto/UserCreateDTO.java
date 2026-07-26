package com.armada.admin.model.dto;

import java.util.List;

/** 新建系统用户参数。 */
public record UserCreateDTO(String username, String nickname, String password, List<Long> roleIds) {
}
